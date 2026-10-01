package com.example.adminmemo

import android.content.Intent
import android.os.Bundle
import androidx.cardview.widget.CardView

class ContentManageHubActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_content_manage_hub)

        findViewById<CardView>(R.id.cardManageStudy).setOnClickListener {
            startActivity(
                Intent(this, SubjectSelectActivity::class.java)
                    .putExtra(EXTRA_PURPOSE, PURPOSE_MANAGE)
            )
        }
        findViewById<CardView>(R.id.cardManageCase).setOnClickListener {
            startActivity(
                Intent(this, SubjectSelectActivity::class.java)
                    .putExtra(EXTRA_PURPOSE, PURPOSE_MANAGE_CASE)
            )
        }
    }
}
