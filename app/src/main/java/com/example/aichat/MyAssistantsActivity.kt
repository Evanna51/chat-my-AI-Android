package com.example.aichat

import android.content.Intent
import android.os.Bundle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class MyAssistantsActivity : ThemedActivity() {

    private lateinit var store: MyAssistantStore
    private lateinit var adapter: MyAssistantListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_assistants)

        store = MyAssistantStore(this)

        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        val recycler: RecyclerView = findViewById(R.id.recyclerAssistants)
        recycler.layoutManager = GridLayoutManager(this, 2)
        adapter = MyAssistantListAdapter()
        adapter.setOnAssistantClickListener { a ->
            val i = Intent(this, EditMyAssistantActivity::class.java)
            i.putExtra(EditMyAssistantActivity.EXTRA_ASSISTANT_ID, a.id)
            startActivity(i)
        }
        recycler.adapter = adapter

        val btnCreate: MaterialButton = findViewById(R.id.btnCreateAssistant)
        btnCreate.setOnClickListener {
            startActivity(Intent(this, EditMyAssistantActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        adapter.setItems(store.getAll())
    }
}
