package com.example.aichat;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AllConversationsActivity extends ThemedActivity {
    private AppDatabase db;
    private SessionMetaStore metaStore;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Map<String, Boolean> collapsedMap = new LinkedHashMap<>();
    private AllConversationsAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_all_conversations);
        db = AppDatabase.getInstance(this);
        metaStore = new SessionMetaStore(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView recycler = findViewById(R.id.recyclerAllConversations);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AllConversationsAdapter();
        adapter.setActionListener(new AllConversationsAdapter.ActionListener() {
            @Override
            public void onOpen(SessionSummary session) {
                Intent i = new Intent(AllConversationsActivity.this, ChatSessionActivity.class);
                i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, session.sessionId);
                startActivity(i);
            }

            @Override
            public void onToggleCategory(String category) {
                boolean collapsed = collapsedMap.containsKey(category) && Boolean.TRUE.equals(collapsedMap.get(category));
                collapsedMap.put(category, !collapsed);
                loadRows();
            }

            @Override
            public void onSetCategory(SessionSummary session) {
                List<String> categories = metaStore.getAllCategories();
                categories.add("自定义…");
                String[] items = categories.toArray(new String[0]);
                new MaterialAlertDialogBuilder(AllConversationsActivity.this)
                        .setTitle("选择分类")
                        .setItems(items, (dialog, which) -> {
                            String category = items[which];
                            if ("自定义…".equals(category)) {
                                showCustomCategoryDialog(session);
                                return;
                            }
                            applyCategory(session, category);
                        })
                        .show();
            }

            @Override
            public void onGenerateOutline(SessionSummary session) {
                if (session == null || session.sessionId == null) return;
                Toast.makeText(AllConversationsActivity.this, "正在生成大纲…", Toast.LENGTH_SHORT).show();
                executor.execute(() -> {
                    List<Message> full = db.messageDao().getBySession(session.sessionId);
                    List<Message> firstTen = new ArrayList<>();
                    if (full != null) {
                        for (int i = 0; i < full.size() && i < 10; i++) {
                            Message m = full.get(i);
                            if (m != null) firstTen.add(m);
                        }
                    }
                    new ChatService(AllConversationsActivity.this).generateSessionOutline(firstTen, new ChatService.ChatCallback() {
                        @Override
                        public void onSuccess(String content) {
                            String outline = content != null ? content.trim() : "";
                            if (outline.isEmpty()) {
                                onError("生成大纲为空");
                                return;
                            }
                            SessionMeta meta = metaStore.get(session.sessionId);
                            meta.outline = outline;
                            metaStore.save(session.sessionId, meta);
                            mainHandler.post(() -> {
                                Toast.makeText(AllConversationsActivity.this, "大纲已更新", Toast.LENGTH_SHORT).show();
                                loadRows();
                            });
                        }

                        @Override
                        public void onError(String message) {
                            mainHandler.post(() ->
                                    Toast.makeText(AllConversationsActivity.this,
                                            message != null && !message.trim().isEmpty() ? message : "生成大纲失败",
                                            Toast.LENGTH_SHORT).show());
                        }
                    });
                });
            }

            @Override
            public void onTogglePin(SessionSummary session) {
                SessionMeta meta = metaStore.get(session.sessionId);
                meta.pinned = !meta.pinned;
                metaStore.save(session.sessionId, meta);
                loadRows();
            }

            @Override
            public void onToggleFavorite(SessionSummary session) {
                SessionMeta meta = metaStore.get(session.sessionId);
                meta.favorite = !meta.favorite;
                if (meta.favorite && (meta.category == null || meta.category.trim().isEmpty() || "默认".equals(meta.category))) {
                    meta.category = "收藏";
                }
                metaStore.save(session.sessionId, meta);
                loadRows();
            }

            @Override
            public void onDelete(SessionSummary session) {
                if (session == null || session.sessionId == null) return;
                new MaterialAlertDialogBuilder(AllConversationsActivity.this)
                        .setTitle("删除对话")
                        .setMessage("删除后不可恢复，确认删除？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (dialog, which) -> executor.execute(() -> {
                            db.messageDao().deleteBySession(session.sessionId);
                            metaStore.remove(session.sessionId);
                            new SessionChatOptionsStore(AllConversationsActivity.this).remove(session.sessionId);
                            new SessionAssistantBindingStore(AllConversationsActivity.this).remove(session.sessionId);
                            mainHandler.post(() -> {
                                Toast.makeText(AllConversationsActivity.this, "对话已删除", Toast.LENGTH_SHORT).show();
                                loadRows();
                            });
                        }))
                        .show();
            }
        });
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadRows();
    }

    private void loadRows() {
        executor.execute(() -> {
            List<SessionSummary> all = db.messageDao().getAllSessions();
            List<SessionSummary> safe = all != null ? all : new ArrayList<>();
            Map<String, List<SessionSummary>> byCategory = new LinkedHashMap<>();

            for (SessionSummary s : safe) {
                if (s == null || s.sessionId == null) continue;
                SessionMeta meta = metaStore.get(s.sessionId);
                if (meta.hidden) continue;
                if (meta.title != null && !meta.title.trim().isEmpty()) s.title = meta.title.trim();
                s.outline = meta.outline != null ? meta.outline.trim() : "";
                s.favorite = meta.favorite;
                s.pinned = meta.pinned;
                s.hidden = meta.hidden;
                s.category = (meta.category != null && !meta.category.trim().isEmpty()) ? meta.category.trim() : "默认";
                String category = s.category;
                if (!byCategory.containsKey(category)) byCategory.put(category, new ArrayList<>());
                byCategory.get(category).add(s);
            }

            List<AllConversationsAdapter.Row> rows = new ArrayList<>();
            List<String> categories = new ArrayList<>(byCategory.keySet());
            Collections.sort(categories);
            if (categories.remove("置顶")) categories.add(0, "置顶");
            if (categories.remove("收藏")) categories.add(0, "收藏");

            for (String category : categories) {
                List<SessionSummary> sessions = byCategory.get(category);
                if (sessions == null) continue;
                sessions.sort(Comparator
                        .comparing((SessionSummary s) -> s.pinned).reversed()
                        .thenComparing((SessionSummary s) -> s.lastAt, Comparator.reverseOrder()));

                AllConversationsAdapter.Row header = new AllConversationsAdapter.Row();
                header.header = true;
                header.category = category;
                header.count = sessions.size();
                header.collapsed = collapsedMap.containsKey(category) && Boolean.TRUE.equals(collapsedMap.get(category));
                rows.add(header);

                if (header.collapsed) continue;
                for (SessionSummary s : sessions) {
                    AllConversationsAdapter.Row item = new AllConversationsAdapter.Row();
                    item.header = false;
                    item.session = s;
                    rows.add(item);
                }
            }
            mainHandler.post(() -> adapter.setRows(rows));
        });
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void applyCategory(SessionSummary session, String category) {
        if (session == null || session.sessionId == null) return;
        String safeCategory = category != null ? category.trim() : "";
        if (safeCategory.isEmpty()) safeCategory = "默认";
        SessionMeta meta = metaStore.get(session.sessionId);
        meta.category = safeCategory;
        metaStore.save(session.sessionId, meta);
        loadRows();
    }

    private void showCustomCategoryDialog(SessionSummary session) {
        EditText input = new EditText(this);
        input.setHint("输入分类名");
        input.setSingleLine(true);
        new MaterialAlertDialogBuilder(this)
                .setTitle("自定义分类")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String category = input.getText() != null ? input.getText().toString().trim() : "";
                    if (category.isEmpty()) {
                        Toast.makeText(this, "分类名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    applyCategory(session, category);
                })
                .show();
    }
}
