package com.example.adminmemo

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class ContentRevision(
    val id: String,
    val updatedAt: Long
)

object FirebaseSyncManager {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    val currentUser get() = auth.currentUser

    fun isSignedIn() = currentUser != null

    private val progressFiles = listOf(
        "memos.json",
        "wrong_ids.json",
        "wrong_notes.json",
        "study_log.json",
        "memory_levels.json",
        "today_tts.json",
        "recall.json",
        "recall_quests.json",
        "study_load.json",
        "read_pass.json",
        "exam_chance.json",
        "study_week.json",
        "today_study.json",
        "skill_pulse.json"
    )

    private val contentFiles = listOf(
        "user_cards.json",
        "edited_cards.json",
        "deleted_ids.json",
        "custom_order.json",
        "user_cases.json",
        "edited_cases.json",
        "deleted_case_ids.json"
    )

    private val backupFiles = progressFiles + contentFiles
    private const val KEEP_REVISIONS = 5
    private const val PROGRESS_DEBOUNCE_MS = 2_000L
    private const val TODAY_PUSH_MS = 400L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var syncing = false
    @Volatile private var pendingAfterSync = false
    @Volatile private var scheduled = false
    @Volatile private var todayScheduled = false
    @Volatile private var dirty = false
    @Volatile private var applyingRemote = false
    private var appCtx: Context? = null
    private var listenReg: ListenerRegistration? = null
    private var listenTodayReg: ListenerRegistration? = null
    private var listenTickReg: ListenerRegistration? = null
    private var listenHitsReg: ListenerRegistration? = null
    private var authWired = false
    private val uiListeners = CopyOnWriteArrayList<() -> Unit>()
    private val syncCompletes = CopyOnWriteArrayList<(Boolean, String?) -> Unit>()
    var lastError: String? = null
        private set

    private data class CloudProgress(
        val backup: DocumentSnapshot?,
        val today: DocumentSnapshot?,
        val shards: Map<String, DocumentSnapshot>,
        val hits: DocumentSnapshot? = null
    )

    fun start(context: Context) {
        appCtx = context.applicationContext
        if (authWired) {
            attachProgressListener()
            return
        }
        authWired = true
        auth.addAuthStateListener {
            attachProgressListener()
            val ctx = appCtx
            if (ctx != null && currentUser != null) {
                syncProgressNow(ctx, null)
            }
        }
    }

    fun addUiListener(listener: () -> Unit) {
        uiListeners.add(listener)
    }

    fun removeUiListener(listener: () -> Unit) {
        uiListeners.remove(listener)
    }

    private fun notifyUi() {
        mainHandler.post { uiListeners.forEach { it() } }
    }

    private fun dataCol(uid: String) =
        db.collection("users").document(uid).collection("data")

    private fun progressDocId(name: String) = "p_" + safeCloudKey(name)

    private fun attachProgressListener() {
        listenReg?.remove()
        listenReg = null
        listenTodayReg?.remove()
        listenTodayReg = null
        listenTickReg?.remove()
        listenTickReg = null
        listenHitsReg?.remove()
        listenHitsReg = null
        val uid = currentUser?.uid ?: return
        val col = dataCol(uid)
        listenReg = col.document("backup").addSnapshotListener { snap, err ->
            if (err != null) {
                lastError = err.localizedMessage
                notifyUi()
                return@addSnapshotListener
            }
            if (snap != null && snap.metadata.hasPendingWrites()) return@addSnapshotListener
            onRemoteHint()
        }
        listenTodayReg = col.document("today").addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
            onRemoteHint()
        }
        listenTickReg = col.document("tick").addSnapshotListener { snap, err ->
            if (err != null) return@addSnapshotListener
            if (snap != null && snap.metadata.hasPendingWrites()) return@addSnapshotListener
            onRemoteHint()
        }
        listenHitsReg = col.document("hits").addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) return@addSnapshotListener
            if (snap.metadata.hasPendingWrites()) return@addSnapshotListener
            val ctx = appCtx ?: return@addSnapshotListener
            TodayStudyStore.applyHits(ctx, snap.get("days"))
            DailyQuestStore.overlayHitsFromWeek(ctx)
            notifyUi()
        }
    }

    private fun onRemoteHint() {
        if (applyingRemote || syncing) {
            pendingAfterSync = true
            return
        }
        val ctx = appCtx ?: return
        applyingRemote = true
        fetchProgress({ pack ->
            applyCloudProgress(ctx, pack) {
                applyingRemote = false
                notifyUi()
                val extra = pendingAfterSync || dirty
                pendingAfterSync = false
                if (extra) {
                    mainHandler.post { runSmart(ctx, null) }
                }
            }
        }, {
            applyingRemote = false
        })
    }

    private val debounceRun = Runnable {
        scheduled = false
        val ctx = appCtx ?: return@Runnable
        runSmart(ctx, null)
    }

    private val todayPushRun = Runnable {
        todayScheduled = false
        val ctx = appCtx ?: return@Runnable
        if (!isSignedIn()) return@Runnable
        TodayStudyStore.captureFromStores(ctx)
        val uid = currentUser?.uid
        pushHitsDoc(ctx) { _, _ ->
            pushTodayDoc(ctx) { _, _ ->
                if (uid != null) bumpTick(uid, ctx) { _, _ -> }
            }
        }
    }

    /** 진도 파일이 바뀌면 오늘 기록은 바로, 나머지는 2초 뒤 클라우드와 합친다. */
    fun notifyProgressChanged(context: Context) {
        appCtx = context.applicationContext
        TodayStudyStore.captureFromStores(context)
        if (!isSignedIn()) return
        dirty = true
        if (!todayScheduled) {
            todayScheduled = true
            mainHandler.postDelayed(todayPushRun, TODAY_PUSH_MS)
        }
        if (applyingRemote || syncing) {
            pendingAfterSync = true
            return
        }
        if (scheduled) return
        scheduled = true
        mainHandler.postDelayed(debounceRun, PROGRESS_DEBOUNCE_MS)
    }

    /** 화면을 떠나거나 앱이 백그라운드로 갈 때, 아직 안 올린 진도를 바로 합친다. */
    fun flushPendingProgressSync(context: Context) {
        if (!isSignedIn()) return
        if (!dirty && !scheduled && !pendingAfterSync && !todayScheduled) return
        mainHandler.removeCallbacks(debounceRun)
        mainHandler.removeCallbacks(todayPushRun)
        scheduled = false
        todayScheduled = false
        runSmart(context.applicationContext, null)
    }

    /** 앱을 켤 때·로그인 직후: 클라우드 진도를 받아 합친다. */
    fun syncProgressNow(context: Context, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (!isSignedIn()) {
            onComplete?.invoke(false, null)
            return
        }
        mainHandler.removeCallbacks(debounceRun)
        mainHandler.removeCallbacks(todayPushRun)
        scheduled = false
        todayScheduled = false
        runSmart(context.applicationContext, onComplete)
    }

    private fun runSmart(context: Context, onComplete: ((Boolean, String?) -> Unit)?) {
        if (onComplete != null) syncCompletes.add(onComplete)
        if (syncing) {
            pendingAfterSync = true
            return
        }
        syncing = true
        TodayStudyStore.captureFromStores(context)
        smartSync(context) { ok, msg ->
            lastError = if (ok) null else msg
            syncing = false
            val extra = pendingAfterSync
            pendingAfterSync = false
            if (ok && !extra) dirty = false
            val cbs = ArrayList(syncCompletes)
            syncCompletes.clear()
            cbs.forEach { it(ok, msg) }
            notifyUi()
            if (extra) {
                mainHandler.post { runSmart(context, null) }
            }
        }
    }

    fun smartSync(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        val uid = user.uid
        val userRef = dataCol(uid)
        fetchProgress({ pack ->
            migrateLegacyContent(userRef, pack.backup) {
                if (!pack.hasCloud() && !hasLocalProgress(context)) {
                    onComplete(true, null)
                } else {
                    val cloudTime = pack.updatedAt()
                    AppPrefs.setCloudSyncTimestamp(context, cloudTime)
                    applyCloudProgress(context, pack) {
                        pushProgressToCloud(context) { ok, err ->
                            if (ok) onComplete(true, "폰과 태블릿의 진도·오답을 합쳐 저장했어요")
                            else onComplete(false, err)
                        }
                    }
                }
            }
        }, { e -> onComplete(false, e.localizedMessage) })
    }

    private fun fetchProgress(
        onPack: (CloudProgress) -> Unit,
        onFail: (Exception) -> Unit
    ) {
        val uid = currentUser?.uid ?: run {
            onFail(IllegalStateException("로그인된 사용자가 없어요"))
            return
        }
        val col = dataCol(uid)
        val refs = progressFiles.map { col.document(progressDocId(it)) } +
            col.document("today") + col.document("backup") + col.document("hits")
        val tasks = refs.map { it.get() }
        Tasks.whenAllComplete(tasks)
            .addOnSuccessListener {
                val snaps = tasks.map { task ->
                    if (task.isSuccessful) task.result else null
                }
                onPack(packFrom(snaps))
            }
            .addOnFailureListener { e -> onFail(e) }
    }

    private fun packFrom(snaps: List<DocumentSnapshot?>): CloudProgress {
        val shards = linkedMapOf<String, DocumentSnapshot>()
        progressFiles.forEachIndexed { i, name ->
            snaps.getOrNull(i)?.let { shards[name] = it }
        }
        val today = snaps.getOrNull(progressFiles.size)
        val backup = snaps.getOrNull(progressFiles.size + 1)
        val hits = snaps.getOrNull(progressFiles.size + 2)
        return CloudProgress(backup, today, shards, hits)
    }

    private fun CloudProgress.hasCloud(): Boolean {
        if (today?.exists() == true && todayJsonFrom(today, backup, shards).isNotBlank()) return true
        if (hits?.exists() == true && hits.get("days") != null) return true
        if (shards.values.any { shardJson(it).isNotBlank() }) return true
        return backup?.exists() == true
    }

    private fun CloudProgress.updatedAt(): Long {
        var t = 0L
        t = maxOf(t, today?.getLong("updatedAt") ?: 0L)
        t = maxOf(t, backup?.getLong("updatedAt") ?: 0L)
        t = maxOf(t, hits?.getLong("updatedAt") ?: 0L)
        shards.values.forEach { t = maxOf(t, it.getLong("updatedAt") ?: 0L) }
        return t
    }

    fun pushDataToCloud(context: Context, onComplete: (Boolean, String?) -> Unit) {
        pushProgressToCloud(context, onComplete)
    }

    private fun pushProgressToCloud(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        TodayStudyStore.captureFromStores(context)
        val uid = user.uid
        val names = progressFiles
        var left = names.size + 2
        var okAll = true
        var err: String? = null
        fun step(ok: Boolean, e: String?) {
            if (!ok) {
                okAll = false
                if (err == null) err = e
            }
            left--
            if (left > 0) return
            bumpTick(uid, context) { tickOk, tickErr ->
                onComplete(okAll && tickOk, if (okAll && tickOk) "진도를 클라우드에 합쳐 저장했어요" else err ?: tickErr)
            }
        }
        pushTodayDoc(context) { ok, e -> step(ok, e) }
        pushHitsDoc(context) { ok, e -> step(ok, e) }
        for (name in names) {
            if (name == "today_study.json") {
                step(true, null)
                continue
            }
            pushShard(context, uid, name) { ok, e -> step(ok, e) }
        }
    }


    private fun pushHitsDoc(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        val ref = dataCol(user.uid).document("hits")
        val local = TodayStudyStore.packHits(context)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val merged = TodayStudyStore.unionHits(local, snap.get("days"))
            if (merged.isEmpty()) return@runTransaction true
            tx.set(
                ref,
                hashMapOf(
                    "updatedAt" to System.currentTimeMillis(),
                    "days" to merged
                ),
                SetOptions.merge()
            )
            true
        }
            .addOnSuccessListener { onComplete(true, null) }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    private fun bumpTick(uid: String, context: Context, onComplete: (Boolean, String?) -> Unit) {
        val now = System.currentTimeMillis()
        dataCol(uid).document("tick")
            .set(hashMapOf("updatedAt" to now), SetOptions.merge())
            .addOnSuccessListener {
                AppPrefs.setLocalSyncTimestamp(context, now)
                AppPrefs.setCloudSyncTimestamp(context, now)
                onComplete(true, null)
            }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    private fun pushShard(
        context: Context,
        uid: String,
        name: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val first = localText(context, name)
        if (first.isBlank() || isHollowProgress(name, first)) {
            onComplete(true, null)
            return
        }
        val ref = dataCol(uid).document(progressDocId(name))
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val cloud = shardJson(snap)
            val local = localText(context, name)
            val merged = mergeFile(name, local, cloud)
            if (merged.isBlank() || isHollowProgress(name, merged)) return@runTransaction true
            if (merged == cloud) return@runTransaction true
            tx.set(
                ref,
                hashMapOf(
                    "file" to name,
                    "updatedAt" to System.currentTimeMillis(),
                    "json" to merged
                ),
                SetOptions.merge()
            )
            true
        }
            .addOnSuccessListener { onComplete(true, null) }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    private fun pushTodayDoc(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        TodayStudyStore.captureFromStores(context)
        val uid = user.uid
        val ref = dataCol(uid).document("today")
        val shard = dataCol(uid).document(progressDocId("today_study.json"))
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            val shardSnap = tx.get(shard)
            val local = localText(context, "today_study.json")
            val cloud = TodayStudyStore.mergeCloudJson(
                optionalCloud(snap, "today_study.json").ifBlank { shardJson(shardSnap) },
                shardJson(shardSnap)
            )
            val merged = TodayStudyStore.mergeCloudJson(local, cloud)
            if (TodayStudyStore.isHollowJson(merged)) return@runTransaction true
            if (merged == optionalCloud(snap, "today_study.json") && merged == shardJson(shardSnap)) {
                return@runTransaction true
            }
            val now = System.currentTimeMillis()
            val payload = hashMapOf<String, Any>(
                "updatedAt" to now,
                safeCloudKey("today_study.json") to merged,
                "json" to merged
            )
            tx.set(ref, payload, SetOptions.merge())
            tx.set(
                shard,
                hashMapOf(
                    "file" to "today_study.json",
                    "updatedAt" to now,
                    "json" to merged
                ),
                SetOptions.merge()
            )
            true
        }
            .addOnSuccessListener { onComplete(true, null) }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    private const val CONTENT_CHUNK_CHARS = 180_000

    fun pushContent(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        val col = dataCol(user.uid)
        val now = System.currentTimeMillis()
        writeContentBundle(
            col,
            metaId = "content",
            at = now,
            files = localContentFiles(context),
            fileId = { name, part -> liveContentDocId(name, part) }
        ) { ok, err ->
            if (ok) {
                AppPrefs.setLocalSyncTimestamp(context, now)
                onComplete(true, "본문을 클라우드에 올렸어요")
            } else {
                onComplete(false, err)
            }
        }
    }

    fun listContentRevisions(onResult: (List<ContentRevision>, String?) -> Unit) {
        val user = currentUser ?: run {
            onResult(emptyList(), "로그인된 사용자가 없어요")
            return
        }
        dataCol(user.uid).document("content").get()
            .addOnSuccessListener { latest ->
                val out = mutableListOf<ContentRevision>()
                val latestAt = latest.getLong("updatedAt") ?: 0L
                if (latest.exists() && latestAt > 0L) {
                    out.add(ContentRevision("latest", latestAt))
                }
                contentHistory(latest).forEach { at ->
                    if (at > 0L && out.none { it.updatedAt == at }) {
                        out.add(ContentRevision("r_$at", at))
                    }
                }
                onResult(out.sortedByDescending { it.updatedAt }.take(KEEP_REVISIONS), null)
            }
            .addOnFailureListener { e -> onResult(emptyList(), e.localizedMessage) }
    }

    fun pullContent(context: Context, revisionId: String, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        val col = dataCol(user.uid)
        val metaId = if (revisionId == "latest") "content" else revisionId
        col.document(metaId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    onComplete(false, "그 본문 백업을 찾지 못했어요")
                    return@addOnSuccessListener
                }
                readContentFiles(col, doc, revisionId) { files, err ->
                    if (files == null) {
                        onComplete(false, err ?: "그 본문 백업을 찾지 못했어요")
                        return@readContentFiles
                    }
                    writeNamedFiles(context, files, replaceEmpty = true)
                    CardRepository.invalidateCache()
                    MockExamStore.invalidate()
                    onComplete(true, "그 시점의 본문을 이 기기에 넣었어요")
                }
            }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    private fun localContentFiles(context: Context): Map<String, String> =
        contentFiles.associateWith { localText(context, it) }

    private fun contentHistory(doc: DocumentSnapshot): List<Long> {
        val raw = doc.get("history") as? List<*> ?: return emptyList()
        return raw.mapNotNull { (it as? Number)?.toLong() }.filter { it > 0L }
    }

    private fun contentParts(doc: DocumentSnapshot): Map<String, Int> {
        val raw = doc.get("parts") as? Map<*, *> ?: return emptyMap()
        val out = linkedMapOf<String, Int>()
        contentFiles.forEach { name ->
            val n = (raw[name] as? Number)?.toInt()
                ?: (raw[safeCloudKey(name)] as? Number)?.toInt()
                ?: 0
            if (n > 0) out[name] = n
        }
        return out
    }

    private fun chunkContent(text: String): List<String> {
        if (text.length <= CONTENT_CHUNK_CHARS) return listOf(text)
        return text.chunked(CONTENT_CHUNK_CHARS)
    }

    private fun liveContentDocId(name: String, part: Int): String {
        val stem = progressDocId(name)
        return if (part == 0) stem else "${stem}_p$part"
    }

    private fun revContentDocId(at: Long, name: String, part: Int): String {
        val stem = "r_${at}_" + safeCloudKey(name)
        return if (part == 0) stem else "${stem}_p$part"
    }

    private fun writeContentBundle(
        col: com.google.firebase.firestore.CollectionReference,
        metaId: String,
        at: Long,
        files: Map<String, String>,
        fileId: (String, Int) -> String,
        extra: Map<String, Any> = emptyMap(),
        done: (Boolean, String?) -> Unit
    ) {
        val batch = db.batch()
        val parts = hashMapOf<String, Int>()
        contentFiles.forEach { name ->
            val chunks = chunkContent(files[name].orEmpty())
            parts[safeCloudKey(name)] = chunks.size
            chunks.forEachIndexed { i, chunk ->
                batch.set(
                    col.document(fileId(name, i)),
                    hashMapOf(
                        "file" to name,
                        "updatedAt" to at,
                        "json" to chunk
                    )
                )
            }
        }
        val meta = hashMapOf<String, Any>("updatedAt" to at, "parts" to parts)
        extra.forEach { (k, v) -> meta[k] = v }
        batch.set(col.document(metaId), meta)
        batch.commit()
            .addOnSuccessListener { done(true, null) }
            .addOnFailureListener { e -> done(false, e.localizedMessage) }
    }

    private fun archiveContent(
        col: com.google.firebase.firestore.CollectionReference,
        current: DocumentSnapshot,
        done: (Boolean, String?) -> Unit
    ) {
        val at = current.getLong("updatedAt") ?: System.currentTimeMillis()
        readContentFiles(col, current, "latest") { files, err ->
            if (files == null) {
                done(false, err)
                return@readContentFiles
            }
            writeContentBundle(
                col,
                metaId = "r_$at",
                at = at,
                files = files,
                fileId = { name, part -> revContentDocId(at, name, part) }
            ) { ok, e -> done(ok, e) }
        }
    }

    private fun readContentFiles(
        col: com.google.firebase.firestore.CollectionReference,
        meta: DocumentSnapshot,
        revisionId: String,
        done: (Map<String, String>?, String?) -> Unit
    ) {
        val parts = contentParts(meta)
        if (parts.isEmpty()) {
            val files = contentFiles.associateWith { cloudText(meta, it) }
            if (files.values.any { it.isNotBlank() }) {
                done(files, null)
            } else {
                done(null, "그 본문 백업을 찾지 못했어요")
            }
            return
        }
        val at = meta.getLong("updatedAt") ?: 0L
        val refs = mutableListOf<Pair<String, Int>>()
        parts.forEach { (name, n) ->
            repeat(n) { i -> refs.add(name to i) }
        }
        val tasks = refs.map { (name, part) ->
            val id = if (revisionId == "latest" || meta.id == "content") {
                liveContentDocId(name, part)
            } else {
                revContentDocId(at, name, part)
            }
            col.document(id).get()
        }
        Tasks.whenAllComplete(tasks)
            .addOnSuccessListener {
                val grouped = linkedMapOf<String, Array<String>>()
                parts.forEach { (name, n) -> grouped[name] = Array(n) { "" } }
                tasks.forEachIndexed { i, task ->
                    if (!task.isSuccessful) return@forEachIndexed
                    val snap = task.result ?: return@forEachIndexed
                    val name = refs[i].first
                    val part = refs[i].second
                    grouped[name]?.set(part, asCloudString(snap.get("json")))
                }
                val files = contentFiles.associateWith { name ->
                    grouped[name]?.joinToString("") ?: ""
                }
                done(files, null)
            }
            .addOnFailureListener { e -> done(null, e.localizedMessage) }
    }

    private fun pruneContentArchives(
        col: com.google.firebase.firestore.CollectionReference,
        keep: List<Long>,
        done: () -> Unit
    ) {
        val keepSet = keep.toSet()
        col.document("content").get()
            .addOnSuccessListener { doc ->
                val extra = contentHistory(doc).filter { it !in keepSet }
                if (extra.isEmpty()) {
                    done()
                    return@addOnSuccessListener
                }
                var left = extra.size
                extra.forEach { at ->
                    col.document("r_$at").delete().addOnCompleteListener {
                        left--
                        if (left <= 0) done()
                    }
                }
            }
            .addOnFailureListener { done() }
    }

    private fun contentPayload(context: Context, now: Long): HashMap<String, Any> {
        val payload = hashMapOf<String, Any>("updatedAt" to now)
        for (name in contentFiles) {
            payload[safeCloudKey(name)] = localText(context, name)
        }
        return payload
    }

    private fun migrateLegacyContent(
        userRef: com.google.firebase.firestore.CollectionReference,
        backup: DocumentSnapshot?,
        then: () -> Unit
    ) {
        if (backup == null || !backup.exists()) {
            then()
            return
        }
        val hasLegacy = contentFiles.any { cloudText(backup, it).isNotBlank() }
        if (!hasLegacy) {
            then()
            return
        }
        userRef.document("content").get()
            .addOnSuccessListener { content ->
                if (content.exists() && (content.getLong("updatedAt") ?: 0L) > 0L) {
                    then()
                    return@addOnSuccessListener
                }
                val now = backup.getLong("updatedAt") ?: System.currentTimeMillis()
                val payload = hashMapOf<String, Any>("updatedAt" to now)
                contentFiles.forEach { payload[safeCloudKey(it)] = cloudText(backup, it) }
                userRef.document("content").set(payload)
                    .addOnCompleteListener { then() }
            }
            .addOnFailureListener { then() }
    }

    /** Firestore는 점(.)을 하위 경로로 해석할 수 있어, 파일명은 밑줄 키로 저장한다. */
    private fun safeCloudKey(fileName: String): String = fileName.replace(".", "_")

    private fun asCloudString(value: Any?): String = when (value) {
        is String -> value
        is Map<*, *> -> (value["json"] as? String).orEmpty()
        else -> ""
    }

    private fun shardJson(doc: DocumentSnapshot?): String {
        if (doc == null || !doc.exists()) return ""
        val j = asCloudString(doc.get("json"))
        if (j.isNotBlank()) return j
        return ""
    }

    /** 예전 점 있는 필드명·중첩 맵·새 밑줄 키를 모두 찾는다. */
    private fun cloudText(doc: DocumentSnapshot, fileName: String): String {
        val stem = fileName.removeSuffix(".json")
        val keys = listOf(safeCloudKey(fileName), fileName, stem)
        for (key in keys) {
            val t = asCloudString(doc.get(FieldPath.of(key)))
            if (t.isNotBlank()) return t
            val t2 = asCloudString(doc.get(key))
            if (t2.isNotBlank()) return t2
        }
        val data = doc.data ?: return ""
        fun walk(map: Map<*, *>): String {
            for ((k, v) in map) {
                val key = k?.toString().orEmpty()
                if (key == fileName || key == safeCloudKey(fileName) || key == stem) {
                    val t = asCloudString(v)
                    if (t.isNotBlank()) return t
                }
                if (v is Map<*, *>) {
                    val inner = walk(v)
                    if (inner.isNotBlank()) return inner
                }
            }
            return ""
        }
        return walk(data)
    }

    private fun hasLocalProgress(context: Context): Boolean =
        progressFiles.any { name ->
            name != "study_load.json" &&
                name != "read_pass.json" &&
                name != "exam_chance.json" &&
                localText(context, name).let { it.isNotBlank() && !isHollowProgress(name, it) }
        }

    private fun isHollowProgress(name: String, json: String): Boolean {
        if (json.isBlank()) return true
        val t = json.replace(" ", "")
        return when (name) {
            "recall_quests.json" ->
                !t.contains("\"recallDone\":true") &&
                    !t.contains("\"quests\":[{") &&
                    !t.contains("\"gradedIds\":[\"") &&
                    !t.contains("\"progress\":") 
            "study_log.json" ->
                !t.contains("\"todayTopics\":[\"") &&
                    (t.contains("\"lastAt\":0") || !t.contains("\"lastAt\":"))
            "recall.json" -> RecallStore.isHollowJson(json)
            "memory_levels.json" -> t == "{}" || t == "[]"
            "read_pass.json" -> ReadPassStore.isHollowJson(json)
            "exam_chance.json" -> ExamChanceStore.isHollowJson(json)
            "study_week.json" -> StudyWeekStore.isHollowJson(json)
            "today_study.json" -> TodayStudyStore.isHollowJson(json)
            "skill_pulse.json" -> SkillPulseStore.isHollowJson(json)
            "wrong_notes.json", "wrong_ids.json", "memos.json" -> t == "{}" || t == "[]"
            else -> false
        }
    }

    private fun optionalCloud(doc: DocumentSnapshot?, fileName: String): String {
        if (doc == null || !doc.exists()) return ""
        return cloudText(doc, fileName)
    }

    private fun todayJsonFrom(
        today: DocumentSnapshot?,
        backup: DocumentSnapshot?,
        shards: Map<String, DocumentSnapshot>
    ): String {
        val a = optionalCloud(today, "today_study.json").ifBlank { shardJson(today) }
        val b = shardJson(shards["today_study.json"])
        val c = optionalCloud(backup, "today_study.json")
        return TodayStudyStore.mergeCloudJson(TodayStudyStore.mergeCloudJson(a, b), c)
    }

    private fun cloudForFile(pack: CloudProgress, name: String): String {
        if (name == "today_study.json") return todayJsonFrom(pack.today, pack.backup, pack.shards)
        val shard = shardJson(pack.shards[name])
        if (shard.isNotBlank()) return shard
        return optionalCloud(pack.backup, name)
    }

    private fun applyTodayJson(context: Context, cloud: String, done: () -> Unit) {
        if (cloud.isBlank() || TodayStudyStore.isHollowJson(cloud)) {
            done()
            return
        }
        TodayStudyStore.foldIfStaleJson(context, cloud)
        val merged = TodayStudyStore.mergeCloudJson(localText(context, "today_study.json"), cloud)
        File(context.filesDir, "today_study.json").writeText(merged, Charsets.UTF_8)
        StoreCaches.invalidateProgress()
        TodayStudyStore.applyMerged(context, merged)
        DailyQuestStore.overlayHitsFromWeek(context)
        TodayStudyStore.captureFromStores(context)
        done()
    }

    private fun applyCloudProgress(
        context: Context,
        pack: CloudProgress,
        done: () -> Unit
    ) {
        TodayStudyStore.rollIntoAccumulated(context)
        val todayCloud = todayJsonFrom(pack.today, pack.backup, pack.shards)
        TodayStudyStore.foldIfStaleJson(context, todayCloud)
        val merged = linkedMapOf<String, String>()
        for (name in progressFiles) {
            val local = localText(context, name)
            val cloud = cloudForFile(pack, name)
            merged[name] = mergeFile(name, local, cloud)
        }
        merged["wrong_ids.json"] = WrongNoteStore.mergeWrongIdsJson(
            localText(context, "wrong_ids.json"),
            cloudForFile(pack, "wrong_ids.json"),
            merged["wrong_notes.json"].orEmpty()
        )
        writeNamedFiles(context, merged)
        TodayStudyStore.applyHits(context, pack.hits?.get("days"))
        TodayStudyStore.applyMerged(context, merged["today_study.json"].orEmpty())
        DailyQuestStore.overlayHitsFromWeek(context)
        TodayStudyStore.captureFromStores(context)
        SkillPulseStore.capture(context)
        CardRepository.invalidateCache()
        done()
    }

    private fun mergeFile(name: String, local: String, cloud: String): String {
        if (local.isBlank()) return cloud
        if (cloud.isBlank()) return local
        if (isHollowProgress(name, local) && !isHollowProgress(name, cloud)) return cloud
        if (isHollowProgress(name, cloud) && !isHollowProgress(name, local)) return local
        return when (name) {
            "memory_levels.json" -> CardStore.mergeMemoryJson(local, cloud)
            "memos.json" -> CardStore.mergeMemosJson(local, cloud)
            "study_log.json" -> StudyProgressStore.mergeCloudJson(local, cloud)
            "wrong_notes.json" -> WrongNoteStore.mergeCloudJson(local, cloud)
            "today_tts.json" -> TodayTtsStore.mergeCloudJson(local, cloud)
            "recall.json" -> RecallStore.mergeCloudJson(local, cloud)
            "recall_quests.json" -> DailyQuestStore.mergeCloudJson(local, cloud)
            "study_load.json" -> StudyLoadStore.mergeCloudJson(local, cloud)
            "read_pass.json" -> ReadPassStore.mergeCloudJson(local, cloud)
            "exam_chance.json" -> ExamChanceStore.mergeCloudJson(local, cloud)
            "study_week.json" -> StudyWeekStore.mergeCloudJson(local, cloud)
            "today_study.json" -> TodayStudyStore.mergeCloudJson(local, cloud)
            "skill_pulse.json" -> SkillPulseStore.mergeCloudJson(local, cloud)
            "wrong_ids.json" -> local
            else -> local
        }
    }

    private fun localText(context: Context, name: String): String {
        val f = File(context.filesDir, name)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    private fun writeNamedFiles(
        context: Context,
        files: Map<String, String>,
        replaceEmpty: Boolean = false
    ) {
        for ((name, json) in files) {
            val f = File(context.filesDir, name)
            if (json.isEmpty()) {
                if (replaceEmpty && f.exists()) f.delete()
                continue
            }
            f.writeText(json, Charsets.UTF_8)
        }
        StoreCaches.invalidateProgress()
        StoreCaches.invalidateCards()
    }

    data class FileStamp(val name: String, val modifiedAt: Long)

    fun localFileStamps(context: Context): List<FileStamp> {
        return backupFiles.map { name ->
            val f = File(context.filesDir, name)
            FileStamp(name, if (f.exists()) f.lastModified() else 0L)
        }
    }

    fun latestLocalFileMillis(context: Context): Long =
        localFileStamps(context).map { it.modifiedAt }.maxOrNull() ?: 0L

    fun fetchCloudUpdatedAt(onResult: (updatedAt: Long?, error: String?) -> Unit) {
        val user = currentUser ?: run {
            onResult(null, "로그인되어 있지 않아요")
            return
        }
        val col = dataCol(user.uid)
        col.document("tick").get()
            .addOnSuccessListener { tick ->
                col.document("today").get()
                    .addOnSuccessListener { today ->
                        val t = maxOf(
                            tick.getLong("updatedAt") ?: 0L,
                            today.getLong("updatedAt") ?: 0L
                        )
                        onResult(t, null)
                    }
                    .addOnFailureListener { e ->
                        onResult(tick.getLong("updatedAt") ?: 0L, e.localizedMessage)
                    }
            }
            .addOnFailureListener { e -> onResult(null, e.localizedMessage) }
    }
}
