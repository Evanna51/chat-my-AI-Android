package com.example.aichat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ThemedActivity {
    private static final String PREFS_RUNTIME = "aichat_runtime";
    private static final String KEY_NOTIF_PERMISSION_REQUESTED = "notif_permission_requested";
    private SessionListAdapter sessionAdapter;
    private HomeAssistantAdapter homeAssistantAdapter;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                // No-op: app can continue without notification permission.
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = AppDatabase.getInstance(this);

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        RecyclerView sessionList = findViewById(R.id.sessionList);
        sessionList.setLayoutManager(new LinearLayoutManager(this));
        sessionAdapter = new SessionListAdapter();
        sessionAdapter.setOnSessionClickListener(s -> {
            Intent i = new Intent(this, ChatSessionActivity.class);
            i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, s.sessionId);
            startActivity(i);
        });
        sessionAdapter.setSessionActionListener(new SessionListAdapter.SessionActionListener() {
            @Override
            public void onHide(SessionSummary s) {
                if (s == null || s.sessionId == null) return;
                executor.execute(() -> {
                    new SessionMetaStore(MainActivity.this).setHidden(s.sessionId, true);
                    mainHandler.post(() -> {
                        Toast.makeText(MainActivity.this, "已隐藏对话", Toast.LENGTH_SHORT).show();
                        loadSessions();
                    });
                });
            }

            @Override
            public void onDelete(SessionSummary s) {
                if (s == null || s.sessionId == null) return;
                new MaterialAlertDialogBuilder(MainActivity.this)
                        .setTitle("删除对话")
                        .setMessage("删除后不可恢复，确认删除？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (dialog, which) -> executor.execute(() -> {
                            db.messageDao().deleteBySession(s.sessionId);
                            new SessionMetaStore(MainActivity.this).remove(s.sessionId);
                            new SessionChatOptionsStore(MainActivity.this).remove(s.sessionId);
                            new SessionAssistantBindingStore(MainActivity.this).remove(s.sessionId);
                            mainHandler.post(() -> {
                                Toast.makeText(MainActivity.this, "对话已删除", Toast.LENGTH_SHORT).show();
                                loadSessions();
                            });
                        }))
                        .show();
            }
        });
        sessionList.setAdapter(sessionAdapter);

        findViewById(R.id.headerMyAssistants).setOnClickListener(v ->
                startActivity(new Intent(this, MyAssistantsActivity.class)));
        findViewById(R.id.btnAllConversations).setOnClickListener(v ->
                startActivity(new Intent(this, AllConversationsActivity.class)));

        RecyclerView recyclerHomeAssistants = findViewById(R.id.recyclerHomeAssistants);
        recyclerHomeAssistants.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        homeAssistantAdapter = new HomeAssistantAdapter();
        homeAssistantAdapter.setOnAssistantClickListener(a -> {
            String sessionId = UUID.randomUUID().toString();
            new SessionAssistantBindingStore(this).bind(sessionId, a.id);
            Intent i = new Intent(this, ChatSessionActivity.class);
            i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId);
            i.putExtra(ChatSessionActivity.EXTRA_ASSISTANT_ID, a.id);
            startActivity(i);
        });
        recyclerHomeAssistants.setAdapter(homeAssistantAdapter);

        EditText inputEdit = findViewById(R.id.inputEdit);
        MaterialButton sendButton = findViewById(R.id.sendButton);
        sendButton.setOnClickListener(v -> sendAndOpenSession(inputEdit));

        loadSessions();
        loadAssistants();
    }

    private void sendAndOpenSession(EditText inputEdit) {
        String text = inputEdit.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入消息", Toast.LENGTH_SHORT).show();
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        Intent i = new Intent(this, ChatSessionActivity.class);
        i.putExtra(ChatSessionActivity.EXTRA_SESSION_ID, sessionId);
        i.putExtra(ChatSessionActivity.EXTRA_INITIAL_MESSAGE, text);
        startActivity(i);
        inputEdit.setText("");
    }

    private void loadSessions() {
        executor.execute(() -> {
            List<SessionSummary> list = db.messageDao().getRecentSessions();
            SessionChatOptionsStore optionsStore = new SessionChatOptionsStore(this);
            SessionMetaStore metaStore = new SessionMetaStore(this);
            List<SessionSummary> merged = new ArrayList<>();
            if (list != null) {
                for (SessionSummary s : list) {
                    if (s == null || s.sessionId == null) continue;
                    SessionChatOptions opts = optionsStore.get(s.sessionId);
                    SessionMeta meta = metaStore.get(s.sessionId);
                    String firstUserMessage = s.title != null ? s.title : "";
                    if (meta != null) {
                        s.favorite = meta.favorite;
                        s.pinned = meta.pinned;
                        s.hidden = meta.hidden;
                        s.category = (meta.category != null && !meta.category.trim().isEmpty())
                                ? meta.category.trim() : "默认";
                        if (meta.avatar != null && !meta.avatar.trim().isEmpty()) {
                            s.avatar = meta.avatar.trim();
                        }
                    }
                    if (s.hidden) continue;
                    if (opts != null) {
                        if (opts.sessionTitle != null && !opts.sessionTitle.trim().isEmpty()) {
                            s.title = opts.sessionTitle.trim();
                        } else {
                            s.title = shortenTitle(firstUserMessage);
                        }
                        if ((s.avatar == null || s.avatar.trim().isEmpty())
                                && opts.sessionAvatar != null && !opts.sessionAvatar.trim().isEmpty()) {
                            s.avatar = opts.sessionAvatar.trim();
                        }
                    } else {
                        s.title = shortenTitle(firstUserMessage);
                    }
                    merged.add(s);
                }
            }
            Collections.sort(merged, Comparator
                    .comparing((SessionSummary s) -> s.pinned).reversed()
                    .thenComparing((SessionSummary s) -> s.lastAt, Comparator.reverseOrder()));
            mainHandler.post(() -> sessionAdapter.setSessions(merged));
        });
    }

    private void loadAssistants() {
        executor.execute(() -> {
            List<MyAssistant> list = new MyAssistantStore(this).getRecent(5);
            mainHandler.post(() -> {
                if (homeAssistantAdapter != null) homeAssistantAdapter.setItems(list);
            });
        });
    }

    private String shortenTitle(String text) {
        String source = text != null ? text.trim() : "";
        if (source.isEmpty()) return "新对话";
        return source.length() > 15 ? source.substring(0, 15) : source;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureNotificationPermissionIfNeeded();
        loadSessions();
        loadAssistants();
        String action = getIntent() != null ? getIntent().getStringExtra("action") : null;
        if ("new_chat".equals(action)) {
            getIntent().removeExtra("action");
        } else if ("export".equals(action)) {
            getIntent().removeExtra("action");
            Toast.makeText(this, "请进入某个对话后，点击右上角菜单导出", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        boolean asked = getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE)
                .getBoolean(KEY_NOTIF_PERMISSION_REQUESTED, false);
        if (asked) return;
        getSharedPreferences(PREFS_RUNTIME, MODE_PRIVATE).edit()
                .putBoolean(KEY_NOTIF_PERMISSION_REQUESTED, true)
                .apply();
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
    }
}
