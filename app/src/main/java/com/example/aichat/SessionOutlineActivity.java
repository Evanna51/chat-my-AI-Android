package com.example.aichat;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SessionOutlineActivity extends ThemedActivity {
    public static final String EXTRA_SESSION_ID = "session_id";

    private String sessionId;
    private SessionOutlineStore outlineStore;
    private SessionOutlineAdapter adapter;
    private TextView textEmpty;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_outline);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (sessionId == null) sessionId = "";
        outlineStore = new SessionOutlineStore(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        textEmpty = findViewById(R.id.textOutlineEmpty);
        RecyclerView recycler = findViewById(R.id.recyclerOutline);
        MaterialButton btnAdd = findViewById(R.id.btnAddOutline);
        MaterialButton btnLeakAudit = findViewById(R.id.btnLeakAudit);

        adapter = new SessionOutlineAdapter();
        adapter.setOnItemActionListener(new SessionOutlineAdapter.OnItemActionListener() {
            @Override
            public void onEdit(SessionOutlineItem item) {
                showEditDialog(item);
            }

            @Override
            public void onDelete(SessionOutlineItem item) {
                if (item == null || item.id == null) return;
                outlineStore.delete(sessionId, item.id);
                refreshList();
            }
        });
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setAdapter(adapter);

        btnAdd.setOnClickListener(v -> showCreateDialog());
        if (btnLeakAudit != null) {
            btnLeakAudit.setOnClickListener(v -> runLeakageAudit());
        }
        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void refreshList() {
        List<SessionOutlineItem> list = outlineStore.getAll(sessionId);
        adapter.setItems(list);
        textEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showCreateDialog() {
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_outline, null);
        EditText editTitle = view.findViewById(R.id.editOutlineTitle);
        EditText editContent = view.findViewById(R.id.editOutlineContent);
        String[] typeValues = new String[] {"chapter", "material", "task", "world", "knowledge"};
        String[] typeLabels = new String[] {"章节", "资料", "任务资料", "世界背景", "知情约束"};
        final int[] selected = new int[] {0};
        TextView textType = view.findViewById(R.id.textOutlineTypeValue);
        textType.setText(typeLabels[0]);
        textType.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("选择类型")
                .setItems(typeLabels, (d, which) -> {
                    selected[0] = which;
                    textType.setText(typeLabels[which]);
                    if (which == 0 && (editTitle.getText() == null || editTitle.getText().toString().trim().isEmpty())) {
                        int next = outlineStore.nextChapterIndex(sessionId);
                        editTitle.setText("章节" + next);
                    }
                })
                .show());

        int next = outlineStore.nextChapterIndex(sessionId);
        editTitle.setText("章节" + next);

        new MaterialAlertDialogBuilder(this)
                .setTitle("新增大纲")
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String title = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
                    String content = editContent.getText() != null ? editContent.getText().toString().trim() : "";
                    if (title.isEmpty()) {
                        Toast.makeText(this, "请填写标题", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    outlineStore.add(sessionId, typeValues[selected[0]], title, content);
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEditDialog(SessionOutlineItem item) {
        if (item == null) return;
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_outline, null);
        EditText editTitle = view.findViewById(R.id.editOutlineTitle);
        EditText editContent = view.findViewById(R.id.editOutlineContent);
        TextView textType = view.findViewById(R.id.textOutlineTypeValue);
        String[] typeValues = new String[] {"chapter", "material", "task", "world", "knowledge"};
        String[] typeLabels = new String[] {"章节", "资料", "任务资料", "世界背景", "知情约束"};
        int defaultType = indexOfType(typeValues, outlineStore.normalizeType(item.type));
        final int[] selected = new int[] {defaultType};
        textType.setText(typeLabels[defaultType]);
        textType.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("选择类型")
                .setItems(typeLabels, (d, which) -> {
                    selected[0] = which;
                    textType.setText(typeLabels[which]);
                })
                .show());

        editTitle.setText(item.title != null ? item.title : "");
        editContent.setText(item.content != null ? item.content : "");

        new MaterialAlertDialogBuilder(this)
                .setTitle("编辑大纲")
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    item.type = typeValues[selected[0]];
                    item.title = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
                    item.content = editContent.getText() != null ? editContent.getText().toString().trim() : "";
                    if (item.title.isEmpty()) {
                        Toast.makeText(this, "请填写标题", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    outlineStore.update(sessionId, item);
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int indexOfType(String[] values, String type) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(type)) return i;
        }
        return 0;
    }

    private void runLeakageAudit() {
        List<SessionOutlineItem> all = outlineStore.getAll(sessionId);
        StringBuilder knowledge = new StringBuilder();
        for (SessionOutlineItem item : all) {
            if (item == null || !"knowledge".equals(outlineStore.normalizeType(item.type))) continue;
            String title = item.title != null ? item.title.trim() : "";
            String content = item.content != null ? item.content.trim() : "";
            if (title.isEmpty() && content.isEmpty()) continue;
            knowledge.append("- ");
            if (!title.isEmpty()) knowledge.append(title);
            if (!content.isEmpty()) {
                if (!title.isEmpty()) knowledge.append("：");
                knowledge.append(content);
            }
            knowledge.append("\n");
        }
        if (knowledge.toString().trim().isEmpty()) {
            Toast.makeText(this, "请先添加知情约束类型的大纲", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在审计最近AI回复…", Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            String latestAssistant = "";
            try {
                List<Message> messages = AppDatabase.getInstance(this).messageDao().getBySession(sessionId);
                for (int i = messages.size() - 1; i >= 0; i--) {
                    Message m = messages.get(i);
                    if (m != null && m.role == Message.ROLE_ASSISTANT) {
                        latestAssistant = m.content != null ? m.content.trim() : "";
                        break;
                    }
                }
            } catch (Exception ignored) {}
            String aiText = latestAssistant;
            runOnUiThread(() -> {
                if (aiText.isEmpty()) {
                    Toast.makeText(this, "当前会话还没有可审计的AI回复", Toast.LENGTH_SHORT).show();
                    return;
                }
                new ChatService(this).auditNovelLeakage(knowledge.toString().trim(), aiText, new ChatService.ChatCallback() {
                    @Override
                    public void onSuccess(String content) {
                        runOnUiThread(() -> new MaterialAlertDialogBuilder(SessionOutlineActivity.this)
                                .setTitle("泄密审计结果")
                                .setMessage(content != null ? content.trim() : "")
                                .setPositiveButton(android.R.string.ok, null)
                                .show());
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> Toast.makeText(SessionOutlineActivity.this,
                                message != null && !message.trim().isEmpty() ? message : "审计失败",
                                Toast.LENGTH_LONG).show());
                    }
                });
            });
        });
    }
}
