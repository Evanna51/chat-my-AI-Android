package com.example.aichat;

import android.content.Intent;
import android.os.Bundle;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

public class MyAssistantsActivity extends ThemedActivity {
    private MyAssistantStore store;
    private MyAssistantListAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_assistants);

        store = new MyAssistantStore(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView recycler = findViewById(R.id.recyclerAssistants);
        recycler.setLayoutManager(new GridLayoutManager(this, 2));
        adapter = new MyAssistantListAdapter();
        adapter.setOnAssistantClickListener(a -> {
            Intent i = new Intent(this, EditMyAssistantActivity.class);
            i.putExtra(EditMyAssistantActivity.EXTRA_ASSISTANT_ID, a.id);
            startActivity(i);
        });
        recycler.setAdapter(adapter);

        MaterialButton btnCreate = findViewById(R.id.btnCreateAssistant);
        btnCreate.setOnClickListener(v -> startActivity(new Intent(this, EditMyAssistantActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        adapter.setItems(store.getAll());
    }
}
