package com.example.adminmemo

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.io.File

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
        "recall_quests.json"
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
    private const val PROGRESS_DEBOUNCE_MS = 8_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var syncing = false
    @Volatile private var pendingAfterSync = false
    @Volatile private var scheduled = false
    private var appCtx: Context? = null

    private val debounceRun = Runnable {
        scheduled = false
        val ctx = appCtx ?: return@Runnable
        runSmart(ctx, null)
    }

    /** 진도 파일이 바뀌면 잠시 기다렸다가 클라우드와 합친 뒤 올린다. */
    fun notifyProgressChanged(context: Context) {
        if (!isSignedIn()) return
        appCtx = context.applicationContext
        if (syncing) {
            pendingAfterSync = true
            return
        }
        scheduled = true
        mainHandler.removeCallbacks(debounceRun)
        mainHandler.postDelayed(debounceRun, PROGRESS_DEBOUNCE_MS)
    }

    /** 화면을 떠나거나 앱이 백그라운드로 갈 때, 대기 중인 진도만 바로 합친다. */
    fun flushPendingProgressSync(context: Context) {
        if (!isSignedIn()) return
        if (!scheduled && !pendingAfterSync) return
        mainHandler.removeCallbacks(debounceRun)
        scheduled = false
        runSmart(context.applicationContext, null)
    }

    /** 앱을 켤 때·로그인 직후: 클라우드 진도를 받아 합친다. */
    fun syncProgressNow(context: Context, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (!isSignedIn()) {
            onComplete?.invoke(false, null)
            return
        }
        mainHandler.removeCallbacks(debounceRun)
        scheduled = false
        runSmart(context.applicationContext, onComplete)
    }

    private fun runSmart(context: Context, onComplete: ((Boolean, String?) -> Unit)?) {
        if (syncing) {
            pendingAfterSync = true
            return
        }
        syncing = true
        smartSync(context) { ok, msg ->
            syncing = false
            val extra = pendingAfterSync
            pendingAfterSync = false
            onComplete?.invoke(ok, msg)
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
        val userRef = db.collection("users").document(uid).collection("data")
        userRef.document("backup").get()
            .addOnSuccessListener { doc ->
                migrateLegacyContent(userRef, doc) {
                    if (!doc.exists()) {
                        pushProgressToCloud(context, onComplete)
                    } else {
                        val cloudTime = doc.getLong("updatedAt") ?: 0L
                        AppPrefs.setCloudSyncTimestamp(context, cloudTime)
                        mergeProgressThenPush(context, doc, onComplete)
                    }
                }
            }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    fun pushDataToCloud(context: Context, onComplete: (Boolean, String?) -> Unit) {
        pushProgressToCloud(context, onComplete)
    }

    private fun pushProgressToCloud(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        val now = System.currentTimeMillis()
        val payload = hashMapOf<String, Any>("updatedAt" to now)
        for (name in progressFiles) {
            payload[name] = localText(context, name)
        }
        db.collection("users").document(user.uid).collection("data").document("backup")
            .set(payload, SetOptions.merge())
            .addOnSuccessListener {
                AppPrefs.setLocalSyncTimestamp(context, now)
                AppPrefs.setCloudSyncTimestamp(context, now)
                onComplete(true, "진도를 클라우드에 합쳐 저장했어요")
            }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    fun pushContent(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        val uid = user.uid
        val contentRef = db.collection("users").document(uid).collection("data").document("content")
        val history = db.collection("users").document(uid).collection("content_revisions")
        contentRef.get()
            .addOnSuccessListener { current ->
                fun writeNew() {
                    val now = System.currentTimeMillis()
                    val payload = contentPayload(context, now)
                    contentRef.set(payload)
                        .addOnSuccessListener {
                            pruneRevisions(history) {
                                AppPrefs.setLocalSyncTimestamp(context, now)
                                onComplete(true, "본문을 클라우드에 올렸어요. 이전 본문은 최대 5개 보관해요")
                            }
                        }
                        .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
                }
                if (current.exists() && (current.getLong("updatedAt") ?: 0L) > 0L) {
                    val snap = HashMap(current.data ?: emptyMap())
                    val at = current.getLong("updatedAt") ?: System.currentTimeMillis()
                    history.document(at.toString()).set(snap)
                        .addOnSuccessListener { writeNew() }
                        .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
                } else {
                    writeNew()
                }
            }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    fun listContentRevisions(onResult: (List<ContentRevision>, String?) -> Unit) {
        val user = currentUser ?: run {
            onResult(emptyList(), "로그인된 사용자가 없어요")
            return
        }
        val uid = user.uid
        val out = mutableListOf<ContentRevision>()
        db.collection("users").document(uid).collection("data").document("content")
            .get()
            .addOnSuccessListener { latest ->
                val latestAt = latest.getLong("updatedAt") ?: 0L
                if (latest.exists() && latestAt > 0L) {
                    out.add(ContentRevision("latest", latestAt))
                }
                db.collection("users").document(uid).collection("content_revisions")
                    .orderBy("updatedAt", Query.Direction.DESCENDING)
                    .limit(KEEP_REVISIONS.toLong())
                    .get()
                    .addOnSuccessListener { snap ->
                        snap.documents.forEach { doc ->
                            val at = doc.getLong("updatedAt") ?: 0L
                            if (at > 0L && out.none { it.updatedAt == at }) {
                                out.add(ContentRevision(doc.id, at))
                            }
                        }
                        onResult(out.sortedByDescending { it.updatedAt }.take(KEEP_REVISIONS), null)
                    }
                    .addOnFailureListener { e -> onResult(out, e.localizedMessage) }
            }
            .addOnFailureListener { e -> onResult(emptyList(), e.localizedMessage) }
    }

    fun pullContent(context: Context, revisionId: String, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }
        val uid = user.uid
        val ref = if (revisionId == "latest") {
            db.collection("users").document(uid).collection("data").document("content")
        } else {
            db.collection("users").document(uid).collection("content_revisions").document(revisionId)
        }
        ref.get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    onComplete(false, "그 본문 백업을 찾지 못했어요")
                    return@addOnSuccessListener
                }
                val files = contentFiles.associateWith { doc.getString(it).orEmpty() }
                writeNamedFiles(context, files)
                CardRepository.invalidateCache()
                MockExamStore.invalidate()
                onComplete(true, "그 시점의 본문을 이 기기에 넣었어요")
            }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    private fun pruneRevisions(history: com.google.firebase.firestore.CollectionReference, done: () -> Unit) {
        history.orderBy("updatedAt", Query.Direction.DESCENDING).get()
            .addOnSuccessListener { snap ->
                val extra = snap.documents.drop(KEEP_REVISIONS)
                if (extra.isEmpty()) {
                    done()
                    return@addOnSuccessListener
                }
                var left = extra.size
                extra.forEach { doc ->
                    doc.reference.delete().addOnCompleteListener {
                        left--
                        if (left <= 0) done()
                    }
                }
            }
            .addOnFailureListener { done() }
    }

    private fun contentPayload(context: Context, now: Long): HashMap<String, Any> {
        val payload = hashMapOf<String, Any>("updatedAt" to now)
        for (name in contentFiles) payload[name] = localText(context, name)
        return payload
    }

    private fun migrateLegacyContent(
        userRef: com.google.firebase.firestore.CollectionReference,
        backup: DocumentSnapshot,
        then: () -> Unit
    ) {
        val hasLegacy = contentFiles.any { backup.getString(it).orEmpty().isNotBlank() }
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
                contentFiles.forEach { payload[it] = backup.getString(it).orEmpty() }
                userRef.document("content").set(payload)
                    .addOnCompleteListener { then() }
            }
            .addOnFailureListener { then() }
    }

    private fun mergeProgressThenPush(
        context: Context,
        doc: DocumentSnapshot,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val merged = linkedMapOf<String, String>()
        for (name in progressFiles) {
            val local = localText(context, name)
            val cloud = doc.getString(name).orEmpty()
            merged[name] = mergeFile(name, local, cloud)
        }
        merged["wrong_ids.json"] = WrongNoteStore.mergeWrongIdsJson(
            localText(context, "wrong_ids.json"),
            doc.getString("wrong_ids.json").orEmpty(),
            merged["wrong_notes.json"].orEmpty()
        )
        writeNamedFiles(context, merged)
        CardRepository.invalidateCache()
        pushProgressToCloud(context) { ok, err ->
            if (ok) onComplete(true, "폰과 태블릿의 진도·오답을 합쳐 저장했어요")
            else onComplete(false, err)
        }
    }

    private fun mergeFile(name: String, local: String, cloud: String): String {
        if (local.isBlank()) return cloud
        if (cloud.isBlank()) return local
        return when (name) {
            "memory_levels.json" -> CardStore.mergeMemoryJson(local, cloud)
            "memos.json" -> CardStore.mergeMemosJson(local, cloud)
            "study_log.json" -> StudyProgressStore.mergeCloudJson(local, cloud)
            "wrong_notes.json" -> WrongNoteStore.mergeCloudJson(local, cloud)
            "today_tts.json" -> TodayTtsStore.mergeCloudJson(local, cloud)
            "recall.json" -> RecallStore.mergeCloudJson(local, cloud)
            "recall_quests.json" -> DailyQuestStore.mergeCloudJson(local, cloud)
            "wrong_ids.json" -> local
            else -> local
        }
    }

    private fun localText(context: Context, name: String): String {
        val f = File(context.filesDir, name)
        return if (f.exists()) f.readText(Charsets.UTF_8) else ""
    }

    private fun writeNamedFiles(context: Context, files: Map<String, String>) {
        for ((name, json) in files) {
            val f = File(context.filesDir, name)
            if (json.isEmpty()) {
                if (f.exists()) f.delete()
            } else {
                f.writeText(json, Charsets.UTF_8)
            }
        }
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
        db.collection("users").document(user.uid).collection("data").document("backup")
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    onResult(0L, null)
                    return@addOnSuccessListener
                }
                val cloudTime = doc.getLong("updatedAt") ?: 0L
                onResult(cloudTime, null)
            }
            .addOnFailureListener { e -> onResult(null, e.localizedMessage) }
    }
}
