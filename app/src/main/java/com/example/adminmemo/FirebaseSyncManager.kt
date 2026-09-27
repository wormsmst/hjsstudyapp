package com.example.adminmemo

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File

object FirebaseSyncManager {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    val currentUser get() = auth.currentUser

    fun isSignedIn() = currentUser != null

    private fun sanitizeJsonString(json: String?): String? {
        if (json.isNullOrBlank()) return json
        return json
            .replace("민법-계약법", "민법")
            .replace("민법(계약)", "민법")
            .replace("계약법", "민법")
    }

    /** Smart Sync: Compares local vs cloud timestamps. Pulls if cloud is newer, pushes if local is newer. */
    fun smartSync(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }

        val uid = user.uid
        val localTime = AppPrefs.getLocalSyncTimestamp(context)

        db.collection("users").document(uid).collection("data").document("backup")
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    pushDataToCloud(context, onComplete)
                } else {
                    val cloudTime = doc.getLong("updatedAt") ?: 0L
                    if (cloudTime > localTime) {
                        applyCloudData(context, doc) { success, err ->
                            if (success) {
                                AppPrefs.setLocalSyncTimestamp(context, cloudTime)
                            }
                            onComplete(success, err)
                        }
                    } else if (localTime > cloudTime) {
                        pushDataToCloud(context, onComplete)
                    } else {
                        onComplete(true, null)
                    }
                }
            }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }

    private fun applyCloudData(context: Context, doc: DocumentSnapshot, onComplete: (Boolean, String?) -> Unit) {
        try {
            val userCards = sanitizeJsonString(doc.getString("user_cards"))
            val editedCards = sanitizeJsonString(doc.getString("edited_cards"))
            val memos = sanitizeJsonString(doc.getString("memos"))
            val wrongIds = sanitizeJsonString(doc.getString("wrong_ids"))
            val customOrder = sanitizeJsonString(doc.getString("custom_order"))
            val memoryLevels = sanitizeJsonString(doc.getString("memory_levels"))

            if (!userCards.isNullOrEmpty()) File(context.filesDir, "user_cards.json").writeText(userCards, Charsets.UTF_8)
            if (!editedCards.isNullOrEmpty()) File(context.filesDir, "edited_cards.json").writeText(editedCards, Charsets.UTF_8)
            if (!memos.isNullOrEmpty()) File(context.filesDir, "memos.json").writeText(memos, Charsets.UTF_8)
            if (!wrongIds.isNullOrEmpty()) File(context.filesDir, "wrong_ids.json").writeText(wrongIds, Charsets.UTF_8)
            if (!customOrder.isNullOrEmpty()) File(context.filesDir, "custom_order.json").writeText(customOrder, Charsets.UTF_8)
            if (!memoryLevels.isNullOrEmpty()) File(context.filesDir, "memory_levels.json").writeText(memoryLevels, Charsets.UTF_8)

            onComplete(true, null)
        } catch (e: Exception) {
            onComplete(false, e.localizedMessage)
        }
    }

    /** Push local files to Firestore under users/{uid}/data */
    fun pushDataToCloud(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val user = currentUser ?: run {
            onComplete(false, "로그인된 사용자가 없어요")
            return
        }

        val uid = user.uid
        val now = System.currentTimeMillis()
        AppPrefs.setLocalSyncTimestamp(context, now)

        val userCardsFile = File(context.filesDir, "user_cards.json")
        val editedCardsFile = File(context.filesDir, "edited_cards.json")
        val memosFile = File(context.filesDir, "memos.json")
        val wrongIdsFile = File(context.filesDir, "wrong_ids.json")
        val customOrderFile = File(context.filesDir, "custom_order.json")
        val memoryLevelsFile = File(context.filesDir, "memory_levels.json")

        val payload = hashMapOf<String, Any>(
            "user_cards" to (sanitizeJsonString(if (userCardsFile.exists()) userCardsFile.readText() else "[]") ?: "[]"),
            "edited_cards" to (sanitizeJsonString(if (editedCardsFile.exists()) editedCardsFile.readText() else "{}") ?: "{}"),
            "memos" to (sanitizeJsonString(if (memosFile.exists()) memosFile.readText() else "{}") ?: "{}"),
            "wrong_ids" to (sanitizeJsonString(if (wrongIdsFile.exists()) wrongIdsFile.readText() else "[]") ?: "[]"),
            "custom_order" to (sanitizeJsonString(if (customOrderFile.exists()) customOrderFile.readText() else "{}") ?: "{}"),
            "memory_levels" to (sanitizeJsonString(if (memoryLevelsFile.exists()) memoryLevelsFile.readText() else "{}") ?: "{}"),
            "updatedAt" to now
        )

        db.collection("users").document(uid).collection("data").document("backup")
            .set(payload)
            .addOnSuccessListener { onComplete(true, null) }
            .addOnFailureListener { e -> onComplete(false, e.localizedMessage) }
    }
}
