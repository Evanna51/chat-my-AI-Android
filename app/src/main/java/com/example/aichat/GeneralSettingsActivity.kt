package com.example.aichat

import android.content.Intent
import android.os.Bundle
import com.google.android.material.appbar.MaterialToolbar

class GeneralSettingsActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<android.view.View>(R.id.btnNewChat).setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("action", "new_chat")
            startActivity(i)
            finish()
        }

        findViewById<android.view.View>(R.id.btnExport).setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            i.putExtra("action", "export")
            startActivity(i)
            finish()
        }
    }
}
