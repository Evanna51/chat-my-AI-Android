package com.example.aichat;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
        ChipGroup chipGroupType = view.findViewById(R.id.chipGroupOutlineType);
        final int[] selected = new int[] {0};
        bindTypeChipSelection(view, selected, editTitle);
        if (chipGroupType != null) chipGroupType.check(R.id.chipTypeChapter);
        FormInputScrollHelper.enableFor(editContent);

        int next = outlineStore.nextChapterIndex(sessionId);
        editTitle.setText(getString(R.string.outline_chapter_default_title, next));

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_AIChat_MaterialAlertDialog)
                .setTitle(R.string.outline_add_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String title = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
                    String content = editContent.getText() != null ? editContent.getText().toString().trim() : "";
                    String selectedType = typeValues[selected[0]];
                    if ("knowledge".equals(selectedType)) {
                        List<String> chapterTitles = getChapterTitles();
                        if (chapterTitles.isEmpty()) {
                            Toast.makeText(this, R.string.error_no_chapters_for_knowledge, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showChapterTitlePicker(chapterTitles, "", pickedTitle -> {
                            outlineStore.add(sessionId, selectedType, pickedTitle, content);
                            refreshList();
                        });
                        return;
                    }
                    if (title.isEmpty()) {
                        Toast.makeText(this, R.string.error_outline_title_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    outlineStore.add(sessionId, selectedType, title, content);
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        moveDialogUp(dialog, 40);
    }

    private void showEditDialog(SessionOutlineItem item) {
        if (item == null) return;
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_outline, null);
        EditText editTitle = view.findViewById(R.id.editOutlineTitle);
        EditText editContent = view.findViewById(R.id.editOutlineContent);
        ChipGroup chipGroupType = view.findViewById(R.id.chipGroupOutlineType);
        String[] typeValues = new String[] {"chapter", "material", "task", "world", "knowledge"};
        int defaultType = indexOfType(typeValues, outlineStore.normalizeType(item.type));
        final int[] selected = new int[] {defaultType};
        bindTypeChipSelection(view, selected, null);
        if (chipGroupType != null) chipGroupType.check(typeIndexToChipId(defaultType));
        FormInputScrollHelper.enableFor(editContent);

        editTitle.setText(item.title != null ? item.title : "");
        editContent.setText(item.content != null ? item.content : "");

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.outline_edit_title)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String selectedType = typeValues[selected[0]];
                    String editedTitle = editTitle.getText() != null ? editTitle.getText().toString().trim() : "";
                    String editedContent = editContent.getText() != null ? editContent.getText().toString().trim() : "";
                    if ("knowledge".equals(selectedType)) {
                        List<String> chapterTitles = getChapterTitles();
                        if (chapterTitles.isEmpty()) {
                            Toast.makeText(this, R.string.error_no_chapters_for_knowledge, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        showChapterTitlePicker(chapterTitles, item.title, pickedTitle -> {
                            item.type = selectedType;
                            item.title = pickedTitle;
                            item.content = editedContent;
                            outlineStore.update(sessionId, item);
                            refreshList();
                        });
                        return;
                    }
                    if (editedTitle.isEmpty()) {
                        Toast.makeText(this, R.string.error_outline_title_empty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    item.type = selectedType;
                    item.title = editedTitle;
                    item.content = editedContent;
                    outlineStore.update(sessionId, item);
                    refreshList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        moveDialogUp(dialog, 40);
    }

    private int indexOfType(String[] values, String type) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(type)) return i;
        }
        return 0;
    }

    private void bindTypeChipSelection(View view, int[] selected, EditText editTitleForChapterAutofill) {
        if (view == null || selected == null || selected.length == 0) return;
        bindTypeChip(view, R.id.chipTypeChapter, 0, selected, editTitleForChapterAutofill);
        bindTypeChip(view, R.id.chipTypeMaterial, 1, selected, editTitleForChapterAutofill);
        bindTypeChip(view, R.id.chipTypeTask, 2, selected, editTitleForChapterAutofill);
        bindTypeChip(view, R.id.chipTypeWorld, 3, selected, editTitleForChapterAutofill);
        bindTypeChip(view, R.id.chipTypeKnowledge, 4, selected, editTitleForChapterAutofill);
    }

    private void bindTypeChip(View view, int chipId, int typeIndex, int[] selected, EditText editTitleForChapterAutofill) {
        Chip chip = view.findViewById(chipId);
        if (chip == null) return;
        chip.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) return;
            selected[0] = typeIndex;
            if (typeIndex == 0 && editTitleForChapterAutofill != null
                    && (editTitleForChapterAutofill.getText() == null
                    || editTitleForChapterAutofill.getText().toString().trim().isEmpty())) {
                int nextChapter = outlineStore.nextChapterIndex(sessionId);
                editTitleForChapterAutofill.setText(getString(R.string.outline_chapter_default_title, nextChapter));
            }
        });
    }

    private List<String> getChapterTitles() {
        List<String> all = new ArrayList<>();
        List<SessionOutlineItem> items = outlineStore.getAll(sessionId);
        if (items == null || items.isEmpty()) return all;
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (SessionOutlineItem one : items) {
            if (one == null) continue;
            if (!"chapter".equals(outlineStore.normalizeType(one.type))) continue;
            String title = one.title != null ? one.title.trim() : "";
            if (title.isEmpty()) continue;
            uniq.add(title);
        }
        all.addAll(uniq);
        return all;
    }

    private void showChapterTitlePicker(List<String> chapterTitles,
                                        String defaultTitle,
                                        ChapterTitleSelectionCallback callback) {
        if (chapterTitles == null || chapterTitles.isEmpty() || callback == null) return;
        String preferred = defaultTitle != null ? defaultTitle.trim() : "";
        int defaultIndex = 0;
        if (!preferred.isEmpty()) {
            int idx = chapterTitles.indexOf(preferred);
            if (idx >= 0) defaultIndex = idx;
        }
        String[] titles = chapterTitles.toArray(new String[0]);
        final int[] checked = new int[] {defaultIndex};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.select_related_chapter_title)
                .setSingleChoiceItems(titles, defaultIndex, (d, which) -> checked[0] = which)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int idx = checked[0];
                    if (idx < 0 || idx >= titles.length) {
                        Toast.makeText(this, R.string.error_select_chapter, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    callback.onSelected(titles[idx]);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private interface ChapterTitleSelectionCallback {
        void onSelected(String title);
    }

    private void moveDialogUp(androidx.appcompat.app.AlertDialog dialog, int offsetDp) {
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        // Force dialog window background to our custom surface color
        // so it does not fall back to theme-derived tinted backgrounds.
        // window.setBackgroundDrawableResource(R.drawable.bg_material_alert_dialog);
        WindowManager.LayoutParams params = window.getAttributes();
        if (params == null) return;
        params.gravity = Gravity.CENTER;
        params.y = -dpToPx(offsetDp);
        window.setAttributes(params);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int typeIndexToChipId(int index) {
        if (index == 1) return R.id.chipTypeMaterial;
        if (index == 2) return R.id.chipTypeTask;
        if (index == 3) return R.id.chipTypeWorld;
        if (index == 4) return R.id.chipTypeKnowledge;
        return R.id.chipTypeChapter;
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
            Toast.makeText(this, R.string.error_no_knowledge_outline, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.auditing_ai_response, Toast.LENGTH_SHORT).show();
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
                        runOnUiThread(() -> new MaterialAlertDialogBuilder(
                                SessionOutlineActivity.this)
                                .setTitle(R.string.leak_audit_result_title)
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
