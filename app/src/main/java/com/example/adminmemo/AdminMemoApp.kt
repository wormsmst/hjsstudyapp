package com.example.adminmemo

import android.app.Application
import com.google.firebase.FirebaseApp

class AdminMemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        FirebaseSyncManager.start(this)
        AppPrefs.applyMemoryResetToOneIfNeeded(this)
        AppPrefs.applyCaseStudyPurgeIfNeeded(this)
    }
}
