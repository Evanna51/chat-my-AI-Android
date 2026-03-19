package com.example.aichat;

import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.core.widget.NestedScrollView;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatSessionActivity extends ThemedActivity {
    private static final String TAG = "ChatSessionActivity";
    private static final String PREFS_CHAPTER_PLAN = "chapter_plan_prefs";
    private static final String KEY_LAST_TARGET_LENGTH = "last_target_length";
    private static final String DEFAULT_TARGET_LENGTH = "3000";

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_INITIAL_MESSAGE = "initial_message";
    public static final String EXTRA_ASSISTANT_ID = "assistant_id";
    private static final long STREAM_RENDER_THROTTLE_MS = 24L;
    private static final long STREAM_RENDER_THROTTLE_BUSY_MS = 48L;
    private static final int STREAM_RENDER_BUSY_PENDING_CHARS = 80;
    private static final long STREAM_TYPEWRITER_FRAME_MS = 16L;
    private static final int STREAM_TYPEWRITER_CHARS_PER_FRAME = 4;
    private static final long STREAM_AUTO_SCROLL_THROTTLE_MS = 300L;
    private static final int AUTO_SCROLL_BOTTOM_GAP_DP = 32;
    private static final int WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS = 500;
    private static final int WRITER_ASSISTANT_LAST_SEGMENT_CHARS = 1000;
    private static final String CHARACTER_MEMORY_LOADING_TEXT = "[...正在输入中]";
    private static final int INITIAL_RENDER_MESSAGE_LIMIT = 200; // 当前对话窗口保留最近 200 条消息
    private static final int LOAD_MORE_BATCH_SIZE = 50;
    private static final int TOP_LOAD_TRIGGER_GAP_DP = 8;
    private static final long PROACTIVE_POLL_INTERVAL_MS = 30_000L;

    private String sessionId;
    private MessageAdapter historyAdapter;
    private MessageAdapter currentAdapter;
    private final MessageAdapter.AssistantMarkdownStateStore assistantMarkdownStateStore =
            new MessageAdapter.AssistantMarkdownStateStore();
    private MaterialButton sendButtonView;
    private EditText inputEditView;
    private ChatService chatService;
    private AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Message activeThinkingMessage;
    private final Runnable thinkingTicker = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) return;
            if (activeThinkingMessage == null || !activeThinkingMessage.thinkingRunning) return;
            activeThinkingMessage.thinkingElapsedMs =
                    Math.max(0L, System.currentTimeMillis() - activeThinkingMessage.thinkingStartedAt);
            renderStreamingMessageTick(activeThinkingMessage);
            mainHandler.postDelayed(this, 500L);
        }
    };

    private boolean historyExpanded;
    private boolean addActionsExpanded;
    private List<Message> allMessages = new ArrayList<>();
    private NestedScrollView scrollMessagesView;
    private boolean autoScrollToBottomEnabled = true;
    private String pendingInitialMessage;
    private String assistantId;
    private boolean writerAssistant;
    private boolean characterAssistant;
    private CharacterMemoryService characterMemoryService;
    private SessionOutlineStore outlineStore;
    private ProactiveMessageSyncManager proactiveSyncManager;
    private SessionChatOptions sessionOptions = new SessionChatOptions();
    private volatile boolean autoNamingInFlight = false;
    private boolean assistantResponseInProgress;
    private boolean streamRenderPending;
    private long lastStreamRenderAt;
    private ChatService.ChatHandle activeChatHandle;
    private Message activeStreamingMessage;
    private long activeResponseToken;
    private long lastStreamAutoScrollAt;
    private Message streamingTargetMessage;
    private final StringBuilder pendingStreamChars = new StringBuilder();
    private boolean streamTypewriterRunning;
    private Message characterMemoryLoadingMessage;
    private TextView loadEarlierMessagesView;
    private TextView quickModelSwitchView;
    private TextView firstDialoguePreviewView;
    private View expandHistoryView;
    private View historyExpandIconView;
    private boolean hasMoreOlderMessages;
    private boolean loadingOlderMessages;
    private int olderRemainingCount;
    private long oldestLoadedCreatedAt = Long.MAX_VALUE;
    private long oldestLoadedMessageId = Long.MAX_VALUE;
    private boolean proactivePollingActive;
    private final Runnable proactivePollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!proactivePollingActive || isFinishing() || isDestroyed()) return;
            if (proactiveSyncManager != null) {
                proactiveSyncManager.syncOnce(new ProactiveMessageSyncManager.SyncCallback() {
                    @Override
                    public void onSessionUpdated(String updatedSessionId) {
                        if (updatedSessionId == null || !updatedSessionId.equals(sessionId)) return;
                        mainHandler.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            loadMessages();
                        });
                    }
                });
            }
            mainHandler.postDelayed(this, PROACTIVE_POLL_INTERVAL_MS);
        }
    };
    private final Runnable streamRenderRunnable = new Runnable() {
        @Override
        public void run() {
            streamRenderPending = false;
            lastStreamRenderAt = System.currentTimeMillis();
            renderStreamingMessageTick(streamingTargetMessage);
        }
    };
    private final Runnable streamTypewriterRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                streamTypewriterRunning = false;
                return;
            }
            if (streamingTargetMessage == null) {
                streamTypewriterRunning = false;
                pendingStreamChars.setLength(0);
                return;
            }
            if (pendingStreamChars.length() <= 0) {
                streamTypewriterRunning = false;
                return;
            }
            int take = Math.min(STREAM_TYPEWRITER_CHARS_PER_FRAME, pendingStreamChars.length());
            String delta = pendingStreamChars.substring(0, take);
            pendingStreamChars.delete(0, take);
            String old = streamingTargetMessage.content != null ? streamingTargetMessage.content : "";
            streamingTargetMessage.content = old + delta;
            boolean rendered = historyAdapter != null && historyAdapter.renderStreamingMessageIfVisible(streamingTargetMessage);
            rendered |= currentAdapter != null && currentAdapter.renderStreamingMessageIfVisible(streamingTargetMessage);
            if (!rendered) {
                scheduleStreamRender();
            } else {
                maybeAutoScrollOnStreamTick();
            }
            if (pendingStreamChars.length() > 0) {
                mainHandler.postDelayed(this, STREAM_TYPEWRITER_FRAME_MS);
            } else {
                streamTypewriterRunning = false;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_session);

        sessionId = getIntent().getStringExtra(EXTRA_SESSION_ID);
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        pendingInitialMessage = getIntent().getStringExtra(EXTRA_INITIAL_MESSAGE);
        if (pendingInitialMessage != null) {
            getIntent().removeExtra(EXTRA_INITIAL_MESSAGE);
        }
        assistantId = getIntent().getStringExtra(EXTRA_ASSISTANT_ID);
        if (assistantId != null && !assistantId.isEmpty()) {
            new SessionAssistantBindingStore(this).bind(sessionId, assistantId);
        } else {
            assistantId = new SessionAssistantBindingStore(this).getAssistantId(sessionId);
        }
        writerAssistant = resolveWriterAssistant();
        characterAssistant = resolveCharacterAssistant();
        outlineStore = new SessionOutlineStore(this);
        characterMemoryService = new CharacterMemoryService(this);
        proactiveSyncManager = new ProactiveMessageSyncManager(this);

        chatService = new ChatService(this);
        db = AppDatabase.getInstance(this);
        sessionOptions = resolveChatOptions();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        quickModelSwitchView = findViewById(R.id.textQuickModelSwitch);
        if (quickModelSwitchView != null) {
            quickModelSwitchView.setOnClickListener(v -> showQuickModelPicker());
        }
        firstDialoguePreviewView = findViewById(R.id.textFirstDialoguePreview);
        View btnSessionSettings = findViewById(R.id.btnSessionSettings);
        if (btnSessionSettings != null) {
            btnSessionSettings.setOnClickListener(v -> startActivity(new Intent(this, SessionChatSettingsActivity.class)
                    .putExtra(SessionChatSettingsActivity.EXTRA_SESSION_ID, sessionId)));
        }
        View btnSessionMore = findViewById(R.id.btnSessionMore);
        if (btnSessionMore != null) {
            btnSessionMore.setOnClickListener(this::showSessionMoreMenu);
        }
        View btnWriterOutline = findViewById(R.id.btnWriterOutline);
        if (btnWriterOutline != null) {
            btnWriterOutline.setVisibility(writerAssistant ? View.VISIBLE : View.GONE);
            btnWriterOutline.setOnClickListener(v -> {
                if (!writerAssistant) return;
                startActivity(new Intent(this, SessionOutlineActivity.class)
                        .putExtra(SessionOutlineActivity.EXTRA_SESSION_ID, sessionId));
            });
        }

        RecyclerView recyclerHistory = findViewById(R.id.recyclerHistory);
        RecyclerView recyclerCurrent = findViewById(R.id.recyclerCurrent);
        NestedScrollView scrollMessages = findViewById(R.id.scrollMessages);
        scrollMessagesView = scrollMessages;
        EditText inputEdit = findViewById(R.id.inputEdit);
        MaterialButton sendButton = findViewById(R.id.sendButton);
        inputEditView = inputEdit;
        sendButtonView = sendButton;
        View btnAdd = findViewById(R.id.btnAdd);
        View layoutAddActions = findViewById(R.id.layoutAddActions);
        View btnAddFile = findViewById(R.id.btnAddFile);
        View btnAddLocation = findViewById(R.id.btnAddLocation);
        View btnAddTime = findViewById(R.id.btnAddTime);
        View btnAddMore = findViewById(R.id.btnAddMore);
        loadEarlierMessagesView = findViewById(R.id.textLoadEarlierMessages);
        if (loadEarlierMessagesView != null) {
            loadEarlierMessagesView.setOnClickListener(v -> loadOlderMessages());
        }

        View headerHistory = findViewById(R.id.headerHistory);
        expandHistoryView = findViewById(R.id.expandHistory);
        historyExpandIconView = findViewById(R.id.iconHistoryExpand);

        historyAdapter = new MessageAdapter(assistantMarkdownStateStore);
        currentAdapter = new MessageAdapter(assistantMarkdownStateStore);
        historyAdapter.setWriterMode(writerAssistant);
        currentAdapter.setWriterMode(writerAssistant);
        historyAdapter.setDisableAssistantCollapseToggle(characterAssistant);
        currentAdapter.setDisableAssistantCollapseToggle(characterAssistant);
        historyAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant);
        currentAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant);
        MessageAdapter.OnAssistantStateChangedListener assistantStateListener = () -> {
            historyAdapter.notifyDataSetChanged();
            currentAdapter.notifyDataSetChanged();
        };
        historyAdapter.setOnAssistantStateChangedListener(assistantStateListener);
        currentAdapter.setOnAssistantStateChangedListener(assistantStateListener);
        if (recyclerHistory != null) {
            recyclerHistory.setLayoutManager(new LinearLayoutManager(this));
            recyclerHistory.setNestedScrollingEnabled(false);
            recyclerHistory.setAdapter(historyAdapter);
            disableChangeAnimations(recyclerHistory);
        }
        if (recyclerCurrent != null) {
            recyclerCurrent.setLayoutManager(new LinearLayoutManager(this));
            recyclerCurrent.setNestedScrollingEnabled(false);
            recyclerCurrent.setAdapter(currentAdapter);
            disableChangeAnimations(recyclerCurrent);
        }
        bindMessageActions(historyAdapter);
        bindMessageActions(currentAdapter);
        setupAutoCollapseActions(recyclerHistory, recyclerCurrent, scrollMessages);

        if (headerHistory != null && expandHistoryView != null && historyExpandIconView != null) {
            headerHistory.setOnClickListener(v -> {
                setHistoryExpanded(!historyExpanded);
            });
        }

        if (btnAdd != null && layoutAddActions != null) {
            btnAdd.setOnClickListener(v -> {
                addActionsExpanded = !addActionsExpanded;
                layoutAddActions.setVisibility(addActionsExpanded ? View.VISIBLE : View.GONE);
            });
        }
        if (btnAddFile != null) {
            btnAddFile.setOnClickListener(v -> Toast.makeText(this, "导入文件 TODO", Toast.LENGTH_SHORT).show());
        }
        if (btnAddLocation != null) {
            btnAddLocation.setOnClickListener(v -> Toast.makeText(this, "添加位置 TODO", Toast.LENGTH_SHORT).show());
        }
        if (btnAddTime != null) {
            btnAddTime.setOnClickListener(v -> Toast.makeText(this, "添加时间 TODO", Toast.LENGTH_SHORT).show());
        }
        if (btnAddMore != null) {
            btnAddMore.setOnClickListener(v -> Toast.makeText(this, "更多功能 TODO", Toast.LENGTH_SHORT).show());
        }

        updateSendButtonState();
        sendButton.setOnClickListener(v -> {
            if (assistantResponseInProgress) {
                stopLatestResponse();
                return;
            }
            String text = inputEditView != null ? inputEditView.getText().toString().trim() : "";
            if (text.isEmpty()) return;
            if (inputEditView != null) inputEditView.setText("");
            sendMessageFromText(text);
        });
        if (inputEdit != null) {
            inputEdit.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    collapseMessageActions();
                }
            });
        }

        loadMessages();
    }

    private void loadMessages() {
        executor.execute(() -> {
            List<Message> list;
            long oldestCreatedAt = Long.MAX_VALUE;
            long oldestMessageId = Long.MAX_VALUE;
            int olderCount = 0;
            try {
                List<Message> desc = db.messageDao().getLatestBySession(sessionId, INITIAL_RENDER_MESSAGE_LIMIT);
                list = toAscending(desc);
                if (!list.isEmpty()) {
                    Message oldest = list.get(0);
                    oldestCreatedAt = oldest.createdAt;
                    oldestMessageId = oldest.id;
                    olderCount = db.messageDao().countOlderMessages(sessionId, oldestCreatedAt, oldestMessageId);
                }
            } catch (Exception e) {
                list = new ArrayList<>();
            }
            final List<Message> finalList = list != null ? list : new ArrayList<>();
            final long finalOldestCreatedAt = oldestCreatedAt;
            final long finalOldestMessageId = oldestMessageId;
            final int finalOlderCount = olderCount;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                allMessages = new ArrayList<>(finalList);
                oldestLoadedCreatedAt = finalOldestCreatedAt;
                oldestLoadedMessageId = finalOldestMessageId;
                olderRemainingCount = Math.max(0, finalOlderCount);
                hasMoreOlderMessages = olderRemainingCount > 0;
                loadingOlderMessages = false;
                maybeInsertAssistantOpeningMessage();
                if (pendingInitialMessage != null && !pendingInitialMessage.isEmpty()) {
                    String msg = pendingInitialMessage;
                    pendingInitialMessage = null;
                    EditText input = findViewById(R.id.inputEdit);
                    if (input != null) {
                        input.post(() -> sendMessageFromText(msg));
                    } else {
                        sendMessageFromText(msg);
                    }
                    return;
                }
                applyMessagesAndTitle();
                maybeAutoScrollToBottom(true);
                updateLoadEarlierEntryVisibility();
            });
        });
    }

    private void applyMessagesAndTitle() {
        if (isFinishing() || isDestroyed()) return;
        updateFirstDialoguePreview();
        splitAndDisplay();
        if (scrollMessagesView != null) {
            scrollMessagesView.post(this::updateCollapseToggleAffixViewport);
        }
        if (sessionOptions != null && sessionOptions.sessionTitle != null && !sessionOptions.sessionTitle.trim().isEmpty()) {
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(sessionOptions.sessionTitle.trim());
            updateToolbarModelSubtitle();
            return;
        }
        SessionMeta meta = new SessionMetaStore(this).get(sessionId);
        if (meta != null && meta.title != null && !meta.title.trim().isEmpty()) {
            if (getSupportActionBar() != null) getSupportActionBar().setTitle(meta.title.trim());
            updateToolbarModelSubtitle();
            return;
        }
        String title = "";
        for (Message m : allMessages) {
            if (m != null && m.role == Message.ROLE_USER && m.content != null && !m.content.isEmpty()) {
                title = m.content.length() > 25 ? m.content.substring(0, 25) + "…" : m.content;
                break;
            }
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(title.isEmpty() ? "新对话" : title);
        }
        updateToolbarModelSubtitle();
    }

    private void splitAndDisplay() {
        if (isFinishing() || isDestroyed()) return;
        assistantMarkdownStateStore.onAllMessagesChanged(allMessages);
        View cardHistory = findViewById(R.id.cardHistory);
        View textHistoryTitle = findViewById(R.id.textHistoryTitle);
        if (cardHistory == null) return;
        Message latestUser = findLatestByRole(Message.ROLE_USER);
        Message latestAssistant = findLatestByRole(Message.ROLE_ASSISTANT);
        if (characterAssistant) {
            historyAdapter.setPinnedActionMessages(null, null, assistantResponseInProgress);
            currentAdapter.setPinnedActionMessages(null, null, assistantResponseInProgress);
        } else {
            historyAdapter.setPinnedActionMessages(latestUser, latestAssistant, assistantResponseInProgress);
            currentAdapter.setPinnedActionMessages(latestUser, latestAssistant, assistantResponseInProgress);
        }

        int total = allMessages.size();
        if (total <= INITIAL_RENDER_MESSAGE_LIMIT) {
            cardHistory.setVisibility(View.GONE);
            currentAdapter.setMessages(allMessages);
            historyAdapter.setMessages(new ArrayList<>());
        } else {
            cardHistory.setVisibility(View.VISIBLE);
            if (textHistoryTitle != null) textHistoryTitle.setVisibility(View.VISIBLE);
            int split = total - INITIAL_RENDER_MESSAGE_LIMIT;
            List<Message> history = new ArrayList<>(allMessages.subList(0, split));
            List<Message> current = new ArrayList<>(allMessages.subList(split, total));
            historyAdapter.setMessages(history);
            currentAdapter.setMessages(current);
            if (textHistoryTitle != null) {
                ((android.widget.TextView) textHistoryTitle).setText("历史对话 (" + history.size() + "条)");
            }
        }
        maybeAutoScrollToBottom(false);
    }

    private void sendMessageFromText(String text) {
        if (text == null || text.isEmpty()) return;
        if (isFinishing() || isDestroyed()) return;
        setAssistantResponseInProgress(true);
        activeResponseToken++;
        final long responseToken = activeResponseToken;
        activeStreamingMessage = null;
        activeChatHandle = null;

        Message userMsg = new Message(sessionId, Message.ROLE_USER, text);
        executor.execute(() -> {
            try {
                db.messageDao().insert(userMsg);
            } catch (Exception ignored) {}
        });
        allMessages.add(userMsg);
        applyMessagesAndTitle();
        maybeAutoScrollToBottom(true);
        updateToolbarTitle(text);
        maybeAutoGenerateThreadTitle(text);

        List<Message> historyForApi = new ArrayList<>(allMessages);
        if (!historyForApi.isEmpty()) historyForApi.remove(historyForApi.size() - 1);
        historyForApi = buildHistoryForApi(historyForApi);
        SessionChatOptions options = resolveChatOptions();
        final boolean shouldUseCharacterMemory = shouldUseCharacterMemory();
        if (shouldUseCharacterMemory) {
            reportCharacterInteractionAsync(CharacterMemoryApi.ROLE_USER, text);
        }
        final String plainApiUserMessage = buildUserMessageForApi(text);
        final List<Message> finalHistoryForApi = historyForApi;
        final SessionChatOptions finalOptions = options;
        if (shouldAutoChapterPlan(finalOptions)) {
            startChapterPlanFlow(finalHistoryForApi, text, plainApiUserMessage, finalOptions, responseToken, shouldUseCharacterMemory);
            return;
        }
        dispatchChatRequestWithOptionalMemory(finalHistoryForApi, plainApiUserMessage, finalOptions, responseToken, shouldUseCharacterMemory);
    }

    private boolean shouldAutoChapterPlan(SessionChatOptions options) {
        return writerAssistant && options != null && options.autoChapterPlan;
    }

    private void startChapterPlanFlow(List<Message> historyForApi,
                                      String originalInput,
                                      String plainApiUserMessage,
                                      SessionChatOptions options,
                                      long responseToken,
                                      boolean shouldUseCharacterMemory) {
        if (isFinishing() || isDestroyed()) return;
        final boolean[] resolved = new boolean[] {false};
        ChapterPlanDialogController dialogController = showChapterPlanDialog(
                new ChapterPlanDraft(),
                "正在生成章节计划…",
                new ChapterPlanDialogCallback() {
                    @Override
                    public void onCancel() {
                        resolved[0] = true;
                        if (responseToken != activeResponseToken) return;
                        setAssistantResponseInProgress(false);
                        activeChatHandle = null;
                        activeStreamingMessage = null;
                        removeCharacterMemoryLoadingPlaceholder();
                    }

                    @Override
                    public void onConfirm(ChapterPlanDraft edited, boolean addOutline) {
                        resolved[0] = true;
                        if (responseToken != activeResponseToken) return;
                        if (edited == null || !edited.hasAnyContent()) {
                            dispatchChatRequestWithOptionalMemory(historyForApi, plainApiUserMessage, options, responseToken, shouldUseCharacterMemory);
                            return;
                        }
                        persistLastTargetLength(edited.targetLength);
                        if (addOutline) {
                            addChapterPlanToOutline(edited);
                        }
                        String finalUserMessage = composeUserMessageWithChapterPlan(plainApiUserMessage, edited);
                        dispatchChatRequestWithOptionalMemory(historyForApi, finalUserMessage, options, responseToken, shouldUseCharacterMemory);
                    }
                });
        chatService.generateChapterPlanJson(originalInput, plainApiUserMessage, new ChatService.ChatCallback() {
            @Override
            public void onPartial(String delta) {
                mainHandler.post(() -> {
                    if (resolved[0]) return;
                    if (responseToken != activeResponseToken) return;
                    if (isFinishing() || isDestroyed()) return;
                    if (dialogController != null) {
                        dialogController.setStatus(delta != null && !delta.trim().isEmpty()
                                ? delta.trim()
                                : "正在生成章节计划…");
                    }
                });
            }

            @Override
            public void onSuccess(String content) {
                mainHandler.post(() -> {
                    if (resolved[0]) return;
                    if (responseToken != activeResponseToken) return;
                    if (isFinishing() || isDestroyed()) return;
                    ChapterPlanDraft draft = parseChapterPlanDraft(content);
                    if (draft == null) {
                        if (dialogController != null) {
                            dialogController.setStatus("计划解析失败，可手动填写后确认继续");
                        }
                        return;
                    }
                    if (dialogController != null) {
                        dialogController.applyGeneratedDraft(draft);
                        if (draft.hasAnyContent()) {
                            dialogController.setStatus("章节计划已生成，可编辑后确认");
                        } else {
                            dialogController.setStatus("已解析到结构，但字段为空；可手动填写后确认");
                        }
                    }
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> {
                    if (resolved[0]) return;
                    if (responseToken != activeResponseToken) return;
                    if (isFinishing() || isDestroyed()) return;
                    if (dialogController != null) {
                        String msg = (message != null && !message.trim().isEmpty())
                                ? message.trim()
                                : "章节计划生成失败";
                        dialogController.setStatus(msg + "。可手动填写后确认，或直接确认跳过计划。");
                    }
                });
            }
        });
    }

    private void dispatchChatRequestWithOptionalMemory(List<Message> historyForApi,
                                                       String plainApiUserMessage,
                                                       SessionChatOptions options,
                                                       long responseToken,
                                                       boolean shouldUseCharacterMemory) {
        if (!shouldUseCharacterMemory) {
            dispatchChatRequest(historyForApi, plainApiUserMessage, options, responseToken, false);
            return;
        }
        showCharacterMemoryLoadingPlaceholder(responseToken);
        executor.execute(() -> {
            String enrichedUserMessage = plainApiUserMessage;
            try {
                CharacterMemoryApi.MemoryContextResponse memory = characterMemoryService.getMemoryContext(
                        assistantId, sessionId, plainApiUserMessage);
                enrichedUserMessage = buildUserMessageForApiWithMemory(plainApiUserMessage, memory);
            } catch (Exception e) {
                Log.w(TAG, "memory-context failed: " + (e != null ? e.getMessage() : ""));
            }
            final String finalUserMessage = enrichedUserMessage;
            mainHandler.post(() -> {
                if (responseToken != activeResponseToken) return;
                if (isFinishing() || isDestroyed()) return;
                dispatchChatRequest(historyForApi, finalUserMessage, options, responseToken, true);
            });
        });
    }

    private void dispatchChatRequest(List<Message> historyForApi,
                                     String apiUserMessage,
                                     SessionChatOptions options,
                                     long responseToken,
                                     boolean reportAssistantToMemory) {
        boolean streamOutput = true;
        Message streamingAssistant = null;
        if (streamOutput) {
            if (characterMemoryLoadingMessage != null) {
                // Reuse loading placeholder bubble to avoid a blank gap between loading and first token.
                streamingAssistant = characterMemoryLoadingMessage;
                characterMemoryLoadingMessage = null;
                if (streamingAssistant.content == null || streamingAssistant.content.trim().isEmpty()) {
                    streamingAssistant.content = CHARACTER_MEMORY_LOADING_TEXT;
                }
                streamingAssistant.thinkingRunning = false;
                streamingAssistant.thinkingStartedAt = 0L;
                streamingAssistant.thinkingElapsedMs = 0L;
            } else {
                streamingAssistant = new Message(sessionId, Message.ROLE_ASSISTANT, "");
                streamingAssistant.thinkingRunning = false;
                streamingAssistant.thinkingStartedAt = 0L;
                streamingAssistant.thinkingElapsedMs = 0L;
                allMessages.add(streamingAssistant);
                applyMessagesAndTitle();
                maybeAutoScrollToBottom(true);
            }
        }
        Message finalStreamingAssistant = streamingAssistant;
        activeStreamingMessage = finalStreamingAssistant;
        streamingTargetMessage = finalStreamingAssistant;
        stopStreamTypewriter(true);
        try {
            ChatService.ChatHandle chatHandle = chatService.chat(historyForApi, apiUserMessage, options, new ChatService.ChatCallback() {
                private boolean isStale() {
                    return responseToken != activeResponseToken;
                }

                private boolean isUiAlive() {
                    return !isFinishing() && !isDestroyed();
                }

                @Override
                public void onSuccess(String content) {
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (!isUiAlive()) {
                            persistAssistantMessageDetached(content, reportAssistantToMemory);
                            return;
                        }
                        boolean shouldStickBottomAfterDone = autoScrollToBottomEnabled;
                        setAssistantResponseInProgress(false);
                        activeChatHandle = null;
                        activeStreamingMessage = null;
                        String safeContent = content != null ? content : "";
                        removeCharacterMemoryLoadingPlaceholder();
                        if (streamOutput && finalStreamingAssistant != null) {
                            finishThinking(finalStreamingAssistant);
                            stopStreamTypewriter(true);
                            finalStreamingAssistant.content = safeContent;
                            persistSessionMessagesAsync();
                        } else {
                            Message assistantMsg = new Message(sessionId, Message.ROLE_ASSISTANT, safeContent);
                            executor.execute(() -> {
                                try {
                                    db.messageDao().insert(assistantMsg);
                                } catch (Exception ignored) {}
                            });
                            allMessages.add(assistantMsg);
                        }
                        if (reportAssistantToMemory) {
                            reportCharacterInteractionAsync(CharacterMemoryApi.ROLE_ASSISTANT, safeContent);
                        }
                        flushStreamRenderNow();
                        maybeAutoScrollToBottom(shouldStickBottomAfterDone);
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (!isUiAlive()) {
                            return;
                        }
                        setAssistantResponseInProgress(false);
                        activeChatHandle = null;
                        activeStreamingMessage = null;
                        removeCharacterMemoryLoadingPlaceholder();
                        if (finalStreamingAssistant != null) {
                            finishThinking(finalStreamingAssistant);
                        }
                        stopStreamTypewriter(true);
                        if (streamOutput && finalStreamingAssistant != null) {
                            allMessages.remove(finalStreamingAssistant);
                            flushStreamRenderNow();
                        }
                        if (!isFinishing() && !isDestroyed()) {
                            Toast.makeText(ChatSessionActivity.this, message != null ? message : "请求失败", Toast.LENGTH_LONG).show();
                        }
                    });
                }

                @Override
                public void onCancelled() {
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (!isUiAlive()) return;
                        removeCharacterMemoryLoadingPlaceholder();
                        handleResponseStopped(finalStreamingAssistant, reportAssistantToMemory);
                    });
                }

                @Override
                public void onPartial(String delta) {
                    if (!streamOutput || finalStreamingAssistant == null) return;
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (!isUiAlive()) return;
                        if (CHARACTER_MEMORY_LOADING_TEXT.equals(
                                finalStreamingAssistant.content != null ? finalStreamingAssistant.content.trim() : "")) {
                            finalStreamingAssistant.content = "";
                            removeCharacterMemoryLoadingPlaceholder();
                        }
                        finishThinking(finalStreamingAssistant);
                        enqueueStreamDelta(finalStreamingAssistant, delta);
                    });
                }

                @Override
                public void onReasoning(String reasoning) {
                    if (!streamOutput || finalStreamingAssistant == null) return;
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (!isUiAlive()) return;
                        if (CHARACTER_MEMORY_LOADING_TEXT.equals(
                                finalStreamingAssistant.content != null ? finalStreamingAssistant.content.trim() : "")) {
                            finalStreamingAssistant.content = "";
                            removeCharacterMemoryLoadingPlaceholder();
                        }
                        beginThinking(finalStreamingAssistant);
                        finalStreamingAssistant.reasoning = reasoning != null ? reasoning : "";
                        scheduleStreamRender();
                    });
                }

                @Override
                public void onUsage(int promptTokens, int completionTokens, int totalTokens, long elapsedMs) {
                    if (finalStreamingAssistant == null) return;
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (!isUiAlive()) return;
                        finalStreamingAssistant.promptTokens = promptTokens;
                        finalStreamingAssistant.completionTokens = completionTokens;
                        finalStreamingAssistant.totalTokens = totalTokens;
                        finalStreamingAssistant.elapsedMs = elapsedMs;
                        scheduleStreamRender();
                    });
                }
            });
            activeChatHandle = chatHandle;
        } catch (Exception e) {
            setAssistantResponseInProgress(false);
            activeChatHandle = null;
            activeStreamingMessage = null;
            Toast.makeText(this, "发送失败: " + (e != null ? e.getMessage() : ""), Toast.LENGTH_LONG).show();
        }
    }

    private void updateToolbarTitle(String userMsg) {
        if (userMsg == null) return;
        if (sessionOptions != null && sessionOptions.sessionTitle != null && !sessionOptions.sessionTitle.trim().isEmpty()) {
            return;
        }
        SessionMeta meta = new SessionMetaStore(this).get(sessionId);
        if (meta != null && meta.title != null && !meta.title.trim().isEmpty()) return;
        String title = userMsg.length() > 25 ? userMsg.substring(0, 25) + "…" : userMsg;
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(title);
    }

    private void maybeAutoGenerateThreadTitle(String firstUserMessage) {
        if (autoNamingInFlight) {
            Log.d(TAG, "skip auto title: already in flight");
            return;
        }
        if (sessionOptions != null && sessionOptions.sessionTitle != null && !sessionOptions.sessionTitle.trim().isEmpty()) {
            Log.d(TAG, "skip auto title: session title already set");
            return;
        }
        SessionMetaStore metaStore = new SessionMetaStore(this);
        SessionMeta meta = metaStore.get(sessionId);
        if (meta != null && meta.title != null && !meta.title.trim().isEmpty()) {
            Log.d(TAG, "skip auto title: meta title already set");
            return;
        }
        int userCount = countUserMessages();
        if (userCount != 1) {
            Log.d(TAG, "skip auto title: user message count = " + userCount);
            return;
        }
        final String fallbackTitle = buildFallbackThreadTitle(firstUserMessage);
        persistSessionTitle(fallbackTitle, false);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(fallbackTitle);
        }
        updateToolbarModelSubtitle();
        autoNamingInFlight = true;
        Log.d(TAG, "start auto title generation, sessionId=" + sessionId);
        chatService.generateThreadTitle(firstUserMessage, new ChatService.ChatCallback() {
            @Override
            public void onSuccess(String content) {
                autoNamingInFlight = false;
                Log.d(TAG, "auto title success: " + content);
                String generated = content != null ? content.trim() : "";
                if (generated.isEmpty()) {
                    Log.d(TAG, "auto title empty, keep fallback title");
                    return;
                }
                persistSessionTitle(generated, true);
                mainHandler.post(ChatSessionActivity.this::applyMessagesAndTitle);
            }

            @Override
            public void onError(String message) {
                autoNamingInFlight = false;
                Log.e(TAG, "auto title failed: " + message);
            }
        });
    }

    private String buildFallbackThreadTitle(String userMessage) {
        String source = userMessage != null ? userMessage.trim() : "";
        if (source.isEmpty()) return "新对话";
        return source.length() > 10 ? source.substring(0, 10) : source;
    }

    private void persistSessionTitle(String title, boolean overwriteExisting) {
        String trimmed = title != null ? title.trim() : "";
        if (trimmed.isEmpty()) return;
        executor.execute(() -> {
            SessionMetaStore metaStore = new SessionMetaStore(ChatSessionActivity.this);
            SessionMeta meta = metaStore.get(sessionId);
            String metaTitle = meta.title != null ? meta.title.trim() : "";
            if (overwriteExisting || metaTitle.isEmpty()) {
                meta.title = trimmed;
                metaStore.save(sessionId, meta);
            }

            SessionChatOptionsStore optionsStore = new SessionChatOptionsStore(ChatSessionActivity.this);
            SessionChatOptions options = optionsStore.get(sessionId);
            String optionsTitle = options.sessionTitle != null ? options.sessionTitle.trim() : "";
            if (overwriteExisting || optionsTitle.isEmpty()) {
                options.sessionTitle = trimmed;
                optionsStore.save(sessionId, options);
            }

            mainHandler.post(() -> {
                if (sessionOptions == null) sessionOptions = new SessionChatOptions();
                String current = sessionOptions.sessionTitle != null ? sessionOptions.sessionTitle.trim() : "";
                if (overwriteExisting || current.isEmpty()) {
                    sessionOptions.sessionTitle = trimmed;
                }
            });
        });
    }

    private int countUserMessages() {
        int count = 0;
        for (Message m : allMessages) {
            if (m != null && m.role == Message.ROLE_USER) count++;
        }
        return count;
    }

    private SessionChatOptions resolveChatOptions() {
        SessionChatOptionsStore optionsStore = new SessionChatOptionsStore(this);
        SessionChatOptions fromSession = optionsStore.get(sessionId);
        sessionOptions = fromSession != null ? fromSession : new SessionChatOptions();
        // Session-level settings are always the source of truth once initialized/saved.
        if (optionsStore.has(sessionId)) return fromSession;

        SessionChatOptions initialized = initializeSessionOptionsFromAssistantOrGlobal(fromSession);
        optionsStore.save(sessionId, initialized);
        sessionOptions = initialized;
        return initialized;
    }

    private SessionChatOptions initializeSessionOptionsFromAssistantOrGlobal(SessionChatOptions base) {
        SessionChatOptions out = copyOptions(base);

        if (assistantId != null && !assistantId.isEmpty()) {
            MyAssistant assistant = new MyAssistantStore(this).getById(assistantId);
            if (assistant != null) {
                if (assistant.options != null) {
                    out = copyOptions(assistant.options);
                }
                if (out.sessionAvatar == null || out.sessionAvatar.trim().isEmpty()) {
                    out.sessionAvatar = AssistantAvatarHelper.resolveTextAvatar(assistant, assistant.name);
                }
            }
        }

        // Global default fallback: only fill model if still missing after assistant initialization.
        if (out.modelKey == null || out.modelKey.isEmpty()) {
            ModelConfig modelConfig = new ModelConfig(this);
            String fallback = modelConfig.getChatPreset();
            if (fallback == null || fallback.isEmpty()) {
                fallback = modelConfig.getFirstAvailablePreset();
            }
            out.modelKey = fallback != null ? fallback : "";
        }
        return out;
    }

    private SessionChatOptions copyOptions(SessionChatOptions src) {
        SessionChatOptions out = new SessionChatOptions();
        if (src == null) return out;
        out.sessionTitle = src.sessionTitle != null ? src.sessionTitle : "";
        out.sessionAvatar = src.sessionAvatar != null ? src.sessionAvatar : "";
        out.contextMessageCount = src.contextMessageCount;
        out.modelKey = src.modelKey != null ? src.modelKey : "";
        out.systemPrompt = src.systemPrompt != null ? src.systemPrompt : "";
        out.stop = src.stop != null ? src.stop : "";
        out.temperature = src.temperature;
        out.topP = src.topP;
        out.streamOutput = true;
        out.autoChapterPlan = src.autoChapterPlan;
        out.thinking = src.thinking;
        out.googleThinkingBudget = src.googleThinkingBudget;
        return out;
    }

    @Override
    protected void onDestroy() {
        // Keep in-flight response alive when leaving page/app.
        // It can still finish in background and be persisted to DB.
        activeChatHandle = null;
        activeStreamingMessage = null;
        stopStreamTypewriter(true);
        streamingTargetMessage = null;
        mainHandler.removeCallbacks(streamRenderRunnable);
        streamRenderPending = false;
        mainHandler.removeCallbacks(thinkingTicker);
        stopProactivePolling();
        activeThinkingMessage = null;
        if (!assistantResponseInProgress) {
            executor.shutdown();
        }
        super.onDestroy();
    }

    private void persistAssistantMessageDetached(String content, boolean reportAssistantToMemory) {
        String safe = content != null ? content : "";
        assistantResponseInProgress = false;
        Runnable writeTask = () -> {
            try {
                Message assistantMsg = new Message(sessionId, Message.ROLE_ASSISTANT, safe);
                AppDatabase.getInstance(getApplicationContext()).messageDao().insert(assistantMsg);
            } catch (Exception ignored) {}
            if (reportAssistantToMemory) {
                reportCharacterInteractionSafely(CharacterMemoryApi.ROLE_ASSISTANT, safe);
            }
        };
        try {
            executor.execute(writeTask);
            executor.shutdown();
        } catch (Exception ignored) {
            new Thread(writeTask).start();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        writerAssistant = resolveWriterAssistant();
        characterAssistant = resolveCharacterAssistant();
        if (historyAdapter != null) historyAdapter.setWriterMode(writerAssistant);
        if (currentAdapter != null) currentAdapter.setWriterMode(writerAssistant);
        if (historyAdapter != null) historyAdapter.setDisableAssistantCollapseToggle(characterAssistant);
        if (currentAdapter != null) currentAdapter.setDisableAssistantCollapseToggle(characterAssistant);
        if (historyAdapter != null) historyAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant);
        if (currentAdapter != null) currentAdapter.setAutoFocusLatestOnSetMessages(!characterAssistant);
        View btnWriterOutline = findViewById(R.id.btnWriterOutline);
        if (btnWriterOutline != null) {
            btnWriterOutline.setVisibility(writerAssistant ? View.VISIBLE : View.GONE);
        }
        sessionOptions = resolveChatOptions();
        applyMessagesAndTitle();
        startProactivePollingIfNeeded();
    }

    @Override
    protected void onPause() {
        stopProactivePolling();
        super.onPause();
    }

    private void updateToolbarModelSubtitle() {
        if (getSupportActionBar() != null) {
            getSupportActionBar().setSubtitle(null);
        }
        if (quickModelSwitchView == null) return;
        String modelLabel = resolveCurrentModelLabel();
        if (modelLabel.isEmpty()) {
            quickModelSwitchView.setText(getString(R.string.quick_model_switch_placeholder));
        } else {
            quickModelSwitchView.setText(getString(R.string.quick_model_switch_value, modelLabel));
        }
    }

    private String resolveCurrentModelLabel() {
        String modelKey = sessionOptions != null ? sessionOptions.modelKey : "";
        ConfiguredModelPicker.Option option = ConfiguredModelPicker.Option.fromStorageKey(modelKey, this);
        if (option != null && option.displayName != null && !option.displayName.trim().isEmpty()) {
            return option.displayName.trim();
        }
        if (modelKey != null && modelKey.contains(":")) {
            return modelKey.substring(modelKey.indexOf(':') + 1).trim();
        }
        return "";
    }

    private void showQuickModelPicker() {
        List<ConfiguredModelPicker.Option> options = ConfiguredModelPicker.getConfiguredModels(this);
        if (options == null || options.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage("请先在「模型管理」中添加厂商并添加模型")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_model_picker, null);
        RecyclerView recycler = dialogView.findViewById(R.id.recyclerOptions);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("快速切换模型")
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        String currentModelKey = sessionOptions != null ? sessionOptions.modelKey : "";
        ModelPickerAdapter adapter = new ModelPickerAdapter(options, currentModelKey, option -> {
            if (sessionOptions == null) sessionOptions = new SessionChatOptions();
            sessionOptions.modelKey = option.getStorageKey();
            new SessionChatOptionsStore(this).save(sessionId, sessionOptions);
            updateToolbarModelSubtitle();
            dialog.dismiss();
        });
        recycler.setAdapter(adapter);
        dialog.show();
    }

    private void showSessionMoreMenu(View anchor) {
        PopupMenu popupMenu = new PopupMenu(this, anchor);
        popupMenu.getMenu().add(0, 1, 0, getString(R.string.quick_jump_chapters));
        popupMenu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showChapterJumpDialog();
                return true;
            }
            return false;
        });
        popupMenu.show();
    }

    private void showChapterJumpDialog() {
        List<ChapterJumpItem> items = buildChapterJumpItems();
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.no_assistant_chapters, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            ChapterJumpItem one = items.get(i);
            labels[i] = getString(R.string.chapter_jump_item_format, one.index, one.preview);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quick_jump_chapters)
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= items.size()) return;
                    ChapterJumpItem target = items.get(which);
                    scrollToChapterMessage(target.createdAt, target.messageId);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private List<ChapterJumpItem> buildChapterJumpItems() {
        List<ChapterJumpItem> out = new ArrayList<>();
        int chapterIndex = 1;
        for (Message m : allMessages) {
            if (m == null || m.role != Message.ROLE_ASSISTANT) continue;
            String content = m.content != null ? m.content.trim() : "";
            if (content.isEmpty()) continue;
            ChapterJumpItem item = new ChapterJumpItem();
            item.index = chapterIndex++;
            item.messageId = m.id;
            item.createdAt = m.createdAt;
            item.preview = buildChapterPreview(content);
            out.add(item);
        }
        return out;
    }

    private String buildChapterPreview(String content) {
        String[] lines = content.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            if (line == null) continue;
            String one = line.trim();
            if (one.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(one);
            count++;
            if (count >= 2) break;
        }
        String preview = sb.length() > 0 ? sb.toString() : content.trim();
        if (preview.length() > 60) {
            preview = preview.substring(0, 60) + "...";
        }
        return preview;
    }

    private void scrollToChapterMessage(long createdAt, long messageId) {
        if (scrollMessagesView == null) return;
        if (containsMessage(historyAdapter, createdAt, messageId) && !historyExpanded) {
            setHistoryExpanded(true);
        }
        attemptScrollToChapterMessage(createdAt, messageId, 0);
    }

    private void attemptScrollToChapterMessage(long createdAt, long messageId, int attempt) {
        if (scrollMessagesView == null) return;
        boolean moved = scrollToMessageTopInRecycler((RecyclerView) findViewById(R.id.recyclerHistory), historyAdapter, createdAt, messageId);
        if (!moved) {
            moved = scrollToMessageTopInRecycler((RecyclerView) findViewById(R.id.recyclerCurrent), currentAdapter, createdAt, messageId);
        }
        if (moved) return;
        if (attempt >= 12) {
            Toast.makeText(this, R.string.chapter_jump_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        mainHandler.postDelayed(() -> attemptScrollToChapterMessage(createdAt, messageId, attempt + 1), 60L);
    }

    private boolean scrollToMessageTopInRecycler(RecyclerView recyclerView, MessageAdapter adapter, long createdAt, long messageId) {
        if (recyclerView == null || adapter == null || scrollMessagesView == null) return false;
        List<Message> list = adapter.getMessages();
        int pos = -1;
        for (int i = 0; i < list.size(); i++) {
            Message one = list.get(i);
            if (matchesJumpTarget(one, createdAt, messageId)) {
                pos = i;
                break;
            }
        }
        if (pos < 0) return false;
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            ((LinearLayoutManager) layoutManager).scrollToPositionWithOffset(pos, 0);
        } else {
            recyclerView.scrollToPosition(pos);
        }
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(pos);
        View itemView = vh != null ? vh.itemView : null;
        if (itemView == null && layoutManager != null) {
            itemView = layoutManager.findViewByPosition(pos);
        }
        if (itemView == null) return false;
        View timestampView = itemView.findViewById(R.id.textTimestamp);
        int targetY = computeScrollYInContainer(timestampView != null ? timestampView : itemView);
        if (targetY < 0) return false;
        int margin = (int) (8f * getResources().getDisplayMetrics().density);
        scrollMessagesView.smoothScrollTo(0, Math.max(0, targetY - margin));
        return true;
    }

    private boolean containsMessage(MessageAdapter adapter, long createdAt, long messageId) {
        if (adapter == null) return false;
        List<Message> list = adapter.getMessages();
        for (Message one : list) {
            if (matchesJumpTarget(one, createdAt, messageId)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesJumpTarget(Message one, long createdAt, long messageId) {
        if (one == null) return false;
        if (messageId > 0 && one.id > 0) return one.id == messageId;
        return createdAt > 0 && one.createdAt == createdAt;
    }

    private int computeScrollYInContainer(View targetView) {
        if (targetView == null || scrollMessagesView == null) return -1;
        View child = scrollMessagesView.getChildAt(0);
        if (child == null) return -1;
        int y = 0;
        View cursor = targetView;
        while (cursor != null && cursor != child) {
            y += cursor.getTop() - cursor.getScrollY();
            ViewParent parent = cursor.getParent();
            if (!(parent instanceof View)) return -1;
            cursor = (View) parent;
        }
        return cursor == child ? y : -1;
    }

    private void setHistoryExpanded(boolean expanded) {
        historyExpanded = expanded;
        if (expandHistoryView != null) {
            expandHistoryView.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }
        if (historyExpandIconView != null) {
            historyExpandIconView.setRotation(expanded ? 90f : 0f);
        }
    }

    private void updateFirstDialoguePreview() {
        if (firstDialoguePreviewView == null) return;
        String source = "";
        if (sessionOptions != null && sessionOptions.systemPrompt != null) {
            source = sessionOptions.systemPrompt.trim();
        }
        if (source.isEmpty()) {
            firstDialoguePreviewView.setVisibility(View.GONE);
            return;
        }
        String preview = buildFirstDialoguePreviewText(source, 200);
        firstDialoguePreviewView.setText(getString(R.string.system_prompt_preview_value, preview));
        firstDialoguePreviewView.setVisibility(View.VISIBLE);
    }

    private String buildFirstDialoguePreviewText(String text, int maxChars) {
        if (text == null) return "";
        String compact = text.trim();
        if (compact.length() <= maxChars) return compact;
        return compact.substring(0, maxChars) + "...";
    }

    private static class ChapterJumpItem {
        int index;
        String preview;
        long messageId;
        long createdAt;
    }

    private void maybeInsertAssistantOpeningMessage() {
        if (allMessages != null && !allMessages.isEmpty()) return;
        String firstDialogue = "";
        if (assistantId != null && !assistantId.trim().isEmpty()) {
            MyAssistant assistant = new MyAssistantStore(this).getById(assistantId);
            if (assistant != null && assistant.firstDialogue != null) {
                firstDialogue = assistant.firstDialogue.trim();
            }
        }
        if (firstDialogue.isEmpty()) return;
        Message opening = new Message(sessionId, Message.ROLE_ASSISTANT, firstDialogue);
        allMessages.add(opening);
        executor.execute(() -> {
            try {
                db.messageDao().insert(opening);
            } catch (Exception ignored) {}
        });
    }

    private void bindMessageActions(MessageAdapter adapter) {
        if (adapter == null) return;
        adapter.setOnMessageActionListener(new MessageAdapter.OnMessageActionListener() {
            @Override
            public void onRegenerate(Message message) {
                if (message == null || message.role != Message.ROLE_USER) return;
                int idx = indexOf(message);
                if (idx < 0) return;
                String text = message.content != null ? message.content : "";
                while (allMessages.size() > idx) allMessages.remove(allMessages.size() - 1);
                applyMessagesAndTitle();
                persistSessionMessagesAsync();
                sendMessageFromText(text);
            }

            @Override
            public void onEdit(Message message) {
                if (message == null) return;
                showEditDialog(message);
            }

            @Override
            public void onCopy(Message message) {
                if (message == null) return;
                copyText(message.content != null ? message.content : "");
            }

            @Override
            public void onOutline(Message message) {
                if (!writerAssistant || message == null) return;
                summarizeMessageToOutline(message);
            }

            @Override
            public void onDelete(Message message) {
                if (message == null) return;
                int idx = indexOf(message);
                if (idx < 0) return;
                allMessages.remove(idx);
                applyMessagesAndTitle();
                persistSessionMessagesAsync();
            }
        });
    }

    private void summarizeMessageToOutline(Message message) {
        String source = message.content != null ? message.content.trim() : "";
        if (source.isEmpty()) {
            Toast.makeText(this, "消息为空，无法提取", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在提取到大纲…", Toast.LENGTH_SHORT).show();
        chatService.summarizeMessageForOutline(source, new ChatService.ChatCallback() {
            @Override
            public void onSuccess(String content) {
                mainHandler.post(() -> {
                    String summary = content != null ? content.trim() : "";
                    if (summary.isEmpty()) {
                        onError("提取结果为空");
                        return;
                    }
                    int next = outlineStore.nextChapterIndex(sessionId);
                    String title = "章节" + next;
                    outlineStore.add(sessionId, "chapter", title, summary);
                    Toast.makeText(ChatSessionActivity.this, "已添加到大纲：" + title, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String message) {
                mainHandler.post(() -> Toast.makeText(ChatSessionActivity.this,
                        message != null && !message.trim().isEmpty() ? message : "提取失败",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private ChapterPlanDialogController showChapterPlanDialog(ChapterPlanDraft draft,
                                                              String initialStatus,
                                                              ChapterPlanDialogCallback callback) {
        if (draft == null || callback == null) return null;
        if (draft.targetLength == null || draft.targetLength.trim().isEmpty()) {
            draft.targetLength = getDefaultTargetLength();
        }
        View view = getLayoutInflater().inflate(R.layout.dialog_chapter_plan, null);
        TextView textStatus = view.findViewById(R.id.textPlanGenerationStatus);
        TextInputEditText editGoal = view.findViewById(R.id.editPlanChapterGoal);
        TextInputEditText editStart = view.findViewById(R.id.editPlanStartState);
        TextInputEditText editEnd = view.findViewById(R.id.editPlanEndState);
        TextInputEditText editDrives = view.findViewById(R.id.editPlanCharacterDrives);
        TextInputEditText editKnowledge = view.findViewById(R.id.editPlanKnowledgeBoundary);
        TextInputEditText editEvents = view.findViewById(R.id.editPlanEventChain);
        TextInputEditText editForeshadow = view.findViewById(R.id.editPlanForeshadow);
        TextInputEditText editPayoff = view.findViewById(R.id.editPlanPayoff);
        TextInputEditText editForbidden = view.findViewById(R.id.editPlanForbidden);
        TextInputEditText editStyle = view.findViewById(R.id.editPlanStyleGuide);
        TextInputEditText editLength = view.findViewById(R.id.editPlanTargetLength);

        ChapterPlanDialogController controller = new ChapterPlanDialogController(
                null,
                textStatus,
                editGoal,
                editStart,
                editEnd,
                editDrives,
                editKnowledge,
                editEvents,
                editForeshadow,
                editPayoff,
                editForbidden,
                editStyle,
                editLength);
        controller.applyDraft(draft, false);
        controller.setStatus(initialStatus);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("本轮写作计划")
                .setView(view)
                .setNegativeButton("取消", (d, w) -> callback.onCancel())
                .setNeutralButton("确认并加入大纲", (d, w) -> callback.onConfirm(
                        collectChapterPlanDraft(controller.editGoal, controller.editStart, controller.editEnd,
                                controller.editDrives, controller.editKnowledge, controller.editEvents,
                                controller.editForeshadow, controller.editPayoff, controller.editForbidden,
                                controller.editStyle, controller.editLength),
                        true))
                .setPositiveButton("确认", (d, w) -> callback.onConfirm(
                        collectChapterPlanDraft(controller.editGoal, controller.editStart, controller.editEnd,
                                controller.editDrives, controller.editKnowledge, controller.editEvents,
                                controller.editForeshadow, controller.editPayoff, controller.editForbidden,
                                controller.editStyle, controller.editLength),
                        false))
                .show();
        controller.dialog = dialog;
        return controller;
    }

    private ChapterPlanDraft collectChapterPlanDraft(TextInputEditText editGoal,
                                                     TextInputEditText editStart,
                                                     TextInputEditText editEnd,
                                                     TextInputEditText editDrives,
                                                     TextInputEditText editKnowledge,
                                                     TextInputEditText editEvents,
                                                     TextInputEditText editForeshadow,
                                                     TextInputEditText editPayoff,
                                                     TextInputEditText editForbidden,
                                                     TextInputEditText editStyle,
                                                     TextInputEditText editLength) {
        ChapterPlanDraft draft = new ChapterPlanDraft();
        draft.chapterGoal = textOf(editGoal);
        draft.startState = textOf(editStart);
        draft.endState = textOf(editEnd);
        draft.characterDrives = ChapterPlanDraft.parseCharacterDrives(textOf(editDrives));
        draft.knowledgeBoundary = parseLines(textOf(editKnowledge));
        draft.eventChain = parseLines(textOf(editEvents));
        draft.foreshadow = parseLines(textOf(editForeshadow));
        draft.payoff = parseLines(textOf(editPayoff));
        draft.forbidden = parseLines(textOf(editForbidden));
        draft.styleGuide = textOf(editStyle);
        draft.targetLength = textOf(editLength);
        return draft;
    }

    private String composeUserMessageWithChapterPlan(String plainApiUserMessage, ChapterPlanDraft draft) {
        String base = plainApiUserMessage != null ? plainApiUserMessage.trim() : "";
        if (draft == null) return base;
        JsonObject plan = draft.toJson();
        StringBuilder sb = new StringBuilder();
        if (!base.isEmpty()) {
            sb.append(base).append("\n\n");
        }
        sb.append("【本轮写作计划（必须遵循）】\n")
                .append(plan.toString())
                .append("\n\n")
                .append("执行要求：优先遵循本轮计划推进剧情；不得违背知情约束与既有设定。");
        return sb.toString().trim();
    }

    private void addChapterPlanToOutline(ChapterPlanDraft draft) {
        if (draft == null) return;
        int next = outlineStore.nextChapterIndex(sessionId);
        String title = "章节" + next;
        String content = draft.toOutlineText();
        outlineStore.add(sessionId, "chapter", title, content);
        Toast.makeText(this, "已加入大纲：" + title, Toast.LENGTH_SHORT).show();
    }

    private ChapterPlanDraft parseChapterPlanDraft(String json) {
        String raw = json != null ? json.trim() : "";
        if (raw.isEmpty()) return null;
        try {
            JsonObject obj = new JsonParser().parse(raw).getAsJsonObject();
            return ChapterPlanDraft.fromJson(obj);
        } catch (Exception e) {
            return null;
        }
    }

    private String textOf(TextInputEditText edit) {
        if (edit == null || edit.getText() == null) return "";
        return edit.getText().toString().trim();
    }

    private List<String> parseLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            if (line == null) continue;
            String one = line.trim();
            if (!one.isEmpty()) out.add(one);
        }
        return out;
    }

    private String joinLines(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            String one = items.get(i);
            if (one == null || one.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(one.trim());
        }
        return sb.toString();
    }

    private String getDefaultTargetLength() {
        SharedPreferences prefs = getSharedPreferences(PREFS_CHAPTER_PLAN, MODE_PRIVATE);
        String saved = prefs.getString(KEY_LAST_TARGET_LENGTH, "");
        if (saved != null && !saved.trim().isEmpty()) return saved.trim();
        return DEFAULT_TARGET_LENGTH;
    }

    private void persistLastTargetLength(String targetLength) {
        String value = targetLength != null ? targetLength.trim() : "";
        if (value.isEmpty()) return;
        getSharedPreferences(PREFS_CHAPTER_PLAN, MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_TARGET_LENGTH, value)
                .apply();
    }

    private String buildUserMessageForApi(String text) {
        String source = text != null ? text.trim() : "";
        if (!writerAssistant || source.isEmpty()) return source;
        List<SessionOutlineItem> outlines = outlineStore.getAll(sessionId);
        if (outlines == null || outlines.isEmpty()) return source;
        StringBuilder sb = new StringBuilder();
        StringBuilder knowledgeSb = new StringBuilder();
        sb.append(source).append("\n\n");
        sb.append("【写作大纲与资料】\n");
        for (SessionOutlineItem one : outlines) {
            if (one == null) continue;
            String type = "章节";
            if ("material".equals(one.type)) type = "资料";
            else if ("task".equals(one.type)) type = "人物资料";
            else if ("world".equals(one.type)) type = "世界背景";
            else if ("knowledge".equals(one.type)) type = "知情约束";
            String title = one.title != null ? one.title.trim() : "";
            String content = one.content != null ? one.content.trim() : "";
            if (title.isEmpty() && content.isEmpty()) continue;
            sb.append("- [").append(type).append("] ");
            if (!title.isEmpty()) sb.append(title);
            if (!content.isEmpty()) {
                if (!title.isEmpty()) sb.append("：");
                sb.append(content);
            }
            sb.append("\n");
            if ("knowledge".equals(one.type)) {
                knowledgeSb.append("- ");
                if (!title.isEmpty()) knowledgeSb.append(title);
                if (!content.isEmpty()) {
                    if (!title.isEmpty()) knowledgeSb.append("：");
                    knowledgeSb.append(content);
                }
                knowledgeSb.append("\n");
            }
        }
        if (knowledgeSb.length() > 0) {
            sb.append("\n【知情约束（必须遵守）】\n");
            sb.append(knowledgeSb);
            sb.append("1) 角色只能使用其已知信息行动、发言与推理。\n");
            sb.append("2) 未知信息不得被角色直接提及或据此决策。\n");
            sb.append("3) 若需要让角色得知信息，必须先写出获取路径（目击/对话/文件/推理）。\n");
            sb.append("4) 优先保证知情边界，不要把读者已知当成角色已知。\n");
        }
        sb.append("请严格参考以上内容，保持情节、设定、任务线索的一致性与准确性。");
        return sb.toString().trim();
    }

    private String buildUserMessageForApiWithMemory(String baseUserMessage,
                                                    CharacterMemoryApi.MemoryContextResponse memory) {
        String source = baseUserMessage != null ? baseUserMessage.trim() : "";
        if (source.isEmpty()) return source;
        if (memory == null || !memory.shouldUseMemory) return source;
        String guidance = memory.memoryGuidance != null ? memory.memoryGuidance.trim() : "";
        if (guidance.isEmpty()) return source;
        final int maxChars = 1200;
        if (guidance.length() > maxChars) {
            guidance = guidance.substring(0, maxChars);
        }
        return source + "\n\n【角色长期记忆参考】\n" + guidance;
    }

    private List<Message> buildHistoryForApi(List<Message> sourceHistory) {
        List<Message> source = sourceHistory != null ? sourceHistory : new ArrayList<>();
        if (!writerAssistant || source.isEmpty()) return source;
        int lastAssistantIndex = -1;
        for (int i = source.size() - 1; i >= 0; i--) {
            Message one = source.get(i);
            if (one != null && one.role == Message.ROLE_ASSISTANT) {
                lastAssistantIndex = i;
                break;
            }
        }
        List<Message> out = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            Message m = source.get(i);
            if (m == null) continue;
            String content = m.content != null ? m.content : "";
            if (m.role == Message.ROLE_ASSISTANT) {
                if (i == lastAssistantIndex) {
                    content = buildLastAssistantExcerpt(content);
                } else if (content.length() > WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS) {
                    String excerpt = content.substring(0, WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS);
                    content = "【节选说明】以下内容为较早助手回复的前"
                            + WRITER_ASSISTANT_CONTEXT_EXCERPT_MAX_CHARS
                            + "字节选，用于保留关键语气与事实锚点；完整情节请以写作大纲与资料为准。\n"
                            + excerpt;
                }
            }
            Message copy = new Message(sessionId, m.role, content);
            out.add(copy);
        }
        return out;
    }

    private String buildLastAssistantExcerpt(String content) {
        String source = content != null ? content : "";
        int total = source.length();
        int segment = WRITER_ASSISTANT_LAST_SEGMENT_CHARS;
        if (total <= segment * 3) {
            return source;
        }
        String start = source.substring(0, segment);
        int middleStart = Math.max(0, (total - segment) / 2);
        String middle = source.substring(middleStart, middleStart + segment);
        String end = source.substring(total - segment);
        return "【节选说明】以下内容为最近一条助手回复的分段节选（前"
                + segment + "字 / 中间" + segment + "字 / 后" + segment
                + "字），用于保留上下文细节与风格连续性；完整情节请以写作大纲与资料为准。\n"
                + "【前段】\n" + start
                + "\n【中段】\n" + middle
                + "\n【后段】\n" + end;
    }

    private interface ChapterPlanDialogCallback {
        void onCancel();
        void onConfirm(ChapterPlanDraft edited, boolean addOutline);
    }

    private static class ChapterPlanDialogController {
        AlertDialog dialog;
        final TextView textStatus;
        final TextInputEditText editGoal;
        final TextInputEditText editStart;
        final TextInputEditText editEnd;
        final TextInputEditText editDrives;
        final TextInputEditText editKnowledge;
        final TextInputEditText editEvents;
        final TextInputEditText editForeshadow;
        final TextInputEditText editPayoff;
        final TextInputEditText editForbidden;
        final TextInputEditText editStyle;
        final TextInputEditText editLength;

        ChapterPlanDialogController(AlertDialog dialog,
                                    TextView textStatus,
                                    TextInputEditText editGoal,
                                    TextInputEditText editStart,
                                    TextInputEditText editEnd,
                                    TextInputEditText editDrives,
                                    TextInputEditText editKnowledge,
                                    TextInputEditText editEvents,
                                    TextInputEditText editForeshadow,
                                    TextInputEditText editPayoff,
                                    TextInputEditText editForbidden,
                                    TextInputEditText editStyle,
                                    TextInputEditText editLength) {
            this.dialog = dialog;
            this.textStatus = textStatus;
            this.editGoal = editGoal;
            this.editStart = editStart;
            this.editEnd = editEnd;
            this.editDrives = editDrives;
            this.editKnowledge = editKnowledge;
            this.editEvents = editEvents;
            this.editForeshadow = editForeshadow;
            this.editPayoff = editPayoff;
            this.editForbidden = editForbidden;
            this.editStyle = editStyle;
            this.editLength = editLength;
        }

        void setStatus(String status) {
            if (textStatus == null) return;
            String text = status != null ? status.trim() : "";
            textStatus.setText(text.isEmpty() ? "正在生成章节计划…" : text);
        }

        void applyGeneratedDraft(ChapterPlanDraft draft) {
            applyDraft(draft, true);
        }

        void applyDraft(ChapterPlanDraft draft, boolean fillOnlyEmpty) {
            if (draft == null) return;
            applyText(editGoal, draft.chapterGoal, fillOnlyEmpty);
            applyText(editStart, draft.startState, fillOnlyEmpty);
            applyText(editEnd, draft.endState, fillOnlyEmpty);
            applyText(editDrives, draft.characterDrivesToMultiline(), fillOnlyEmpty);
            applyText(editKnowledge, joinLinesStatic(draft.knowledgeBoundary), fillOnlyEmpty);
            applyText(editEvents, joinLinesStatic(draft.eventChain), fillOnlyEmpty);
            applyText(editForeshadow, joinLinesStatic(draft.foreshadow), fillOnlyEmpty);
            applyText(editPayoff, joinLinesStatic(draft.payoff), fillOnlyEmpty);
            applyText(editForbidden, joinLinesStatic(draft.forbidden), fillOnlyEmpty);
            applyText(editStyle, draft.styleGuide, fillOnlyEmpty);
            applyText(editLength, draft.targetLength, fillOnlyEmpty);
        }

        private void applyText(TextInputEditText edit, String value, boolean fillOnlyEmpty) {
            if (edit == null) return;
            String incoming = value != null ? value : "";
            if (fillOnlyEmpty) {
                String current = edit.getText() != null ? edit.getText().toString().trim() : "";
                if (!current.isEmpty()) return;
            }
            edit.setText(incoming);
        }

        private static String joinLinesStatic(List<String> items) {
            if (items == null || items.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String one : items) {
                if (one == null || one.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append(one.trim());
            }
            return sb.toString();
        }
    }

    private static class ChapterPlanDraft {
        String chapterGoal = "";
        String startState = "";
        String endState = "";
        List<CharacterDrive> characterDrives = new ArrayList<>();
        List<String> knowledgeBoundary = new ArrayList<>();
        List<String> eventChain = new ArrayList<>();
        List<String> foreshadow = new ArrayList<>();
        List<String> payoff = new ArrayList<>();
        List<String> forbidden = new ArrayList<>();
        String styleGuide = "";
        String targetLength = "";

        static ChapterPlanDraft fromJson(JsonObject obj) {
            ChapterPlanDraft out = new ChapterPlanDraft();
            if (obj == null) return out;
            out.chapterGoal = getString(obj, "chapterGoal");
            out.startState = getString(obj, "startState");
            out.endState = getString(obj, "endState");
            out.characterDrives = parseCharacterDrives(obj.get("characterDrives"));
            out.knowledgeBoundary = parseStringArray(obj.get("knowledgeBoundary"));
            out.eventChain = parseStringArray(obj.get("eventChain"));
            out.foreshadow = parseStringArray(obj.get("foreshadow"));
            out.payoff = parseStringArray(obj.get("payoff"));
            out.forbidden = parseStringArray(obj.get("forbidden"));
            out.styleGuide = getString(obj, "styleGuide");
            out.targetLength = getString(obj, "targetLength");
            return out;
        }

        JsonObject toJson() {
            JsonObject out = new JsonObject();
            out.addProperty("chapterGoal", chapterGoal != null ? chapterGoal : "");
            out.addProperty("startState", startState != null ? startState : "");
            out.addProperty("endState", endState != null ? endState : "");
            JsonArray drives = new JsonArray();
            if (characterDrives != null) {
                for (CharacterDrive one : characterDrives) {
                    if (one == null) continue;
                    JsonObject item = new JsonObject();
                    item.addProperty("name", one.name != null ? one.name : "");
                    item.addProperty("goal", one.goal != null ? one.goal : "");
                    item.addProperty("misbelief", one.misbelief != null ? one.misbelief : "");
                    item.addProperty("emotion", one.emotion != null ? one.emotion : "");
                    drives.add(item);
                }
            }
            out.add("characterDrives", drives);
            out.add("knowledgeBoundary", toJsonArray(knowledgeBoundary));
            out.add("eventChain", toJsonArray(eventChain));
            out.add("foreshadow", toJsonArray(foreshadow));
            out.add("payoff", toJsonArray(payoff));
            out.add("forbidden", toJsonArray(forbidden));
            out.addProperty("styleGuide", styleGuide != null ? styleGuide : "");
            String length = targetLength != null ? targetLength.trim() : "";
            if (!length.isEmpty()) {
                out.addProperty("targetLength", length);
            }
            return out;
        }

        boolean hasAnyContent() {
            if (chapterGoal != null && !chapterGoal.trim().isEmpty()) return true;
            if (startState != null && !startState.trim().isEmpty()) return true;
            if (endState != null && !endState.trim().isEmpty()) return true;
            if (styleGuide != null && !styleGuide.trim().isEmpty()) return true;
            if (targetLength != null && !targetLength.trim().isEmpty()) return true;
            if (characterDrives != null && !characterDrives.isEmpty()) return true;
            if (knowledgeBoundary != null && !knowledgeBoundary.isEmpty()) return true;
            if (eventChain != null && !eventChain.isEmpty()) return true;
            if (foreshadow != null && !foreshadow.isEmpty()) return true;
            if (payoff != null && !payoff.isEmpty()) return true;
            return forbidden != null && !forbidden.isEmpty();
        }

        String toOutlineText() {
            StringBuilder sb = new StringBuilder();
            if (chapterGoal != null && !chapterGoal.trim().isEmpty()) {
                sb.append("目标：").append(chapterGoal.trim()).append("\n");
            }
            if (startState != null && !startState.trim().isEmpty()) {
                sb.append("起始状态：").append(startState.trim()).append("\n");
            }
            if (endState != null && !endState.trim().isEmpty()) {
                sb.append("结束状态：").append(endState.trim()).append("\n");
            }
            if (eventChain != null && !eventChain.isEmpty()) {
                sb.append("事件链：");
                for (int i = 0; i < eventChain.size(); i++) {
                    String one = eventChain.get(i);
                    if (one == null || one.trim().isEmpty()) continue;
                    if (i > 0) sb.append(" -> ");
                    sb.append(one.trim());
                }
                sb.append("\n");
            }
            if (styleGuide != null && !styleGuide.trim().isEmpty()) {
                sb.append("文风：").append(styleGuide.trim()).append("\n");
            }
            if (targetLength != null && !targetLength.trim().isEmpty()) {
                sb.append("建议篇幅：").append(targetLength.trim());
            }
            return sb.toString().trim();
        }

        String characterDrivesToMultiline() {
            if (characterDrives == null || characterDrives.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (CharacterDrive one : characterDrives) {
                if (one == null) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append(one.name != null ? one.name : "")
                        .append("|")
                        .append(one.goal != null ? one.goal : "")
                        .append("|")
                        .append(one.misbelief != null ? one.misbelief : "")
                        .append("|")
                        .append(one.emotion != null ? one.emotion : "");
            }
            return sb.toString();
        }

        static List<CharacterDrive> parseCharacterDrives(String multiline) {
            List<CharacterDrive> out = new ArrayList<>();
            if (multiline == null || multiline.trim().isEmpty()) return out;
            String[] lines = multiline.split("\\r?\\n");
            for (String line : lines) {
                if (line == null || line.trim().isEmpty()) continue;
                String[] parts = line.split("\\|", -1);
                CharacterDrive drive = new CharacterDrive();
                drive.name = parts.length > 0 ? parts[0].trim() : "";
                drive.goal = parts.length > 1 ? parts[1].trim() : "";
                drive.misbelief = parts.length > 2 ? parts[2].trim() : "";
                drive.emotion = parts.length > 3 ? parts[3].trim() : "";
                out.add(drive);
            }
            return out;
        }

        static List<CharacterDrive> parseCharacterDrives(JsonElement element) {
            List<CharacterDrive> out = new ArrayList<>();
            if (element == null || element.isJsonNull() || !element.isJsonArray()) return out;
            JsonArray arr = element.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement one = arr.get(i);
                if (one == null || one.isJsonNull()) continue;
                CharacterDrive drive = new CharacterDrive();
                if (one.isJsonObject()) {
                    JsonObject obj = one.getAsJsonObject();
                    drive.name = getString(obj, "name");
                    drive.goal = getString(obj, "goal");
                    drive.misbelief = getString(obj, "misbelief");
                    drive.emotion = getString(obj, "emotion");
                } else {
                    drive.goal = one.isJsonPrimitive() ? one.getAsString() : one.toString();
                }
                out.add(drive);
            }
            return out;
        }

        private static List<String> parseStringArray(JsonElement element) {
            List<String> out = new ArrayList<>();
            if (element == null || element.isJsonNull() || !element.isJsonArray()) return out;
            JsonArray arr = element.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                JsonElement one = arr.get(i);
                if (one == null || one.isJsonNull()) continue;
                String text = one.isJsonPrimitive() ? one.getAsString() : one.toString();
                if (text != null && !text.trim().isEmpty()) out.add(text.trim());
            }
            return out;
        }

        private static JsonArray toJsonArray(List<String> source) {
            JsonArray out = new JsonArray();
            if (source == null) return out;
            for (String one : source) {
                if (one == null || one.trim().isEmpty()) continue;
                out.add(one.trim());
            }
            return out;
        }

        private static String getString(JsonObject obj, String key) {
            if (obj == null || key == null || !obj.has(key)) return "";
            try {
                JsonElement e = obj.get(key);
                if (e == null || e.isJsonNull()) return "";
                if (e.isJsonPrimitive()) return e.getAsString();
                return e.toString();
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    private static class CharacterDrive {
        String name = "";
        String goal = "";
        String misbelief = "";
        String emotion = "";
    }

    private boolean resolveWriterAssistant() {
        if (assistantId == null || assistantId.trim().isEmpty()) return false;
        MyAssistant assistant = new MyAssistantStore(this).getById(assistantId);
        return assistant != null && "writer".equals(assistant.type);
    }

    private boolean resolveCharacterAssistant() {
        if (assistantId == null || assistantId.trim().isEmpty()) return false;
        MyAssistant assistant = new MyAssistantStore(this).getById(assistantId);
        return assistant != null && "character".equals(assistant.type);
    }

    private boolean shouldUseCharacterMemory() {
        return characterAssistant
                && assistantId != null
                && !assistantId.trim().isEmpty()
                && characterMemoryService != null
                && characterMemoryService.isEnabled();
    }

    private boolean shouldEnableProactivePolling() {
        if (!shouldUseCharacterMemory()) return false;
        MyAssistant assistant = new MyAssistantStore(this).getById(assistantId);
        return assistant != null && assistant.allowProactiveMessage;
    }

    private void startProactivePollingIfNeeded() {
        stopProactivePolling();
        if (!shouldEnableProactivePolling()) return;
        proactivePollingActive = true;
        mainHandler.post(proactivePollRunnable);
    }

    private void stopProactivePolling() {
        proactivePollingActive = false;
        mainHandler.removeCallbacks(proactivePollRunnable);
    }

    private void showCharacterMemoryLoadingPlaceholder(long responseToken) {
        if (responseToken != activeResponseToken) return;
        if (!shouldUseCharacterMemory()) return;
        removeCharacterMemoryLoadingPlaceholder();
        Message loading = new Message(sessionId, Message.ROLE_ASSISTANT, CHARACTER_MEMORY_LOADING_TEXT);
        loading.createdAt = System.currentTimeMillis();
        characterMemoryLoadingMessage = loading;
        allMessages.add(loading);
        applyMessagesAndTitle();
        maybeAutoScrollToBottom(true);
    }

    private void removeCharacterMemoryLoadingPlaceholder() {
        if (characterMemoryLoadingMessage == null) return;
        allMessages.remove(characterMemoryLoadingMessage);
        characterMemoryLoadingMessage = null;
        applyMessagesAndTitle();
    }

    private void reportCharacterInteractionAsync(String role, String content) {
        executor.execute(() -> reportCharacterInteractionSafely(role, content));
    }

    private void reportCharacterInteractionSafely(String role, String content) {
        if (!shouldUseCharacterMemory()) return;
        String safeRole = role != null ? role.trim() : "";
        String safeContent = content != null ? content.trim() : "";
        if (safeRole.isEmpty() || safeContent.isEmpty()) return;
        try {
            characterMemoryService.reportInteraction(assistantId, sessionId, safeRole, safeContent);
        } catch (Exception e) {
            Log.w(TAG, "report-interaction failed: " + (e != null ? e.getMessage() : ""));
        }
    }

    private int indexOf(Message target) {
        if (target == null) return -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i) == target) return i;
        }
        return -1;
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", text != null ? text : ""));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void showEditDialog(Message message) {
        View view = getLayoutInflater().inflate(R.layout.dialog_edit_message, null);
        TextInputEditText edit = view.findViewById(R.id.editMessageContent);
        if (edit != null) edit.setText(message.content != null ? message.content : "");
        new MaterialAlertDialogBuilder(this)
                .setTitle("编辑消息")
                .setView(view)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (edit == null || edit.getText() == null) return;
                    message.content = edit.getText().toString().trim();
                    applyMessagesAndTitle();
                    persistSessionMessagesAsync();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void persistSessionMessagesAsync() {
        List<Message> snapshot = new ArrayList<>(allMessages);
        executor.execute(() -> {
            try {
                db.messageDao().deleteBySession(sessionId);
                for (Message m : snapshot) {
                    if (m == null) continue;
                    Message item = new Message(sessionId, m.role, m.content != null ? m.content : "");
                    item.createdAt = m.createdAt > 0 ? m.createdAt : System.currentTimeMillis();
                    db.messageDao().insert(item);
                }
            } catch (Exception ignored) {}
        });
    }

    private void setupAutoCollapseActions(RecyclerView recyclerHistory, RecyclerView recyclerCurrent, NestedScrollView scrollMessages) {
        View.OnTouchListener touchCollapse = (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) collapseMessageActions();
            return false;
        };
        if (recyclerHistory != null) {
            recyclerHistory.setOnTouchListener(touchCollapse);
            recyclerHistory.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    if (dy != 0 || dx != 0) collapseMessageActions();
                }
            });
        }
        if (recyclerCurrent != null) {
            recyclerCurrent.setOnTouchListener(touchCollapse);
            recyclerCurrent.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    if (dy != 0 || dx != 0) collapseMessageActions();
                }
            });
        }
        if (scrollMessages != null) {
            scrollMessages.setOnTouchListener(touchCollapse);
            scrollMessages.setOnScrollChangeListener((NestedScrollView.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                updateAutoScrollStateFromPosition();
                if (scrollY != oldScrollY || scrollX != oldScrollX) collapseMessageActions();
                updateLoadEarlierEntryVisibility();
                updateCollapseToggleAffixViewport();
            });
        }
    }

    private void updateCollapseToggleAffixViewport() {
        if (scrollMessagesView == null) return;
        Rect rect = new Rect();
        if (!scrollMessagesView.getGlobalVisibleRect(rect)) return;
        if (historyAdapter != null) {
            historyAdapter.setCollapseToggleAffixViewport(rect.top, rect.bottom);
        }
        if (currentAdapter != null) {
            currentAdapter.setCollapseToggleAffixViewport(rect.top, rect.bottom);
        }
    }

    private void disableChangeAnimations(RecyclerView recyclerView) {
        if (recyclerView == null) return;
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
    }

    private void collapseMessageActions() {
        historyAdapter.clearFocus();
        currentAdapter.clearFocus();
    }

    private void setAssistantResponseInProgress(boolean inProgress) {
        assistantResponseInProgress = inProgress;
        updateSendButtonState();
    }

    private void updateSendButtonState() {
        if (sendButtonView == null) return;
        if (assistantResponseInProgress) {
            sendButtonView.setText(getString(R.string.stop));
            sendButtonView.setIcon(null);
        } else {
            sendButtonView.setText(getString(R.string.send));
            sendButtonView.setIconResource(android.R.drawable.ic_menu_send);
        }
    }

    private void stopLatestResponse() {
        ChatService.ChatHandle handle = activeChatHandle;
        Message target = activeStreamingMessage;
        activeResponseToken++;
        activeChatHandle = null;
        activeStreamingMessage = null;
        removeCharacterMemoryLoadingPlaceholder();
        if (handle != null) {
            try {
                handle.cancel();
            } catch (Exception ignored) {}
        }
        handleResponseStopped(target, shouldUseCharacterMemory());
    }

    private void handleResponseStopped(Message streamingMessage, boolean reportAssistantToMemory) {
        boolean shouldStickBottomAfterDone = autoScrollToBottomEnabled;
        setAssistantResponseInProgress(false);
        if (streamingMessage != null) {
            finishThinking(streamingMessage);
            drainPendingStreamCharsTo(streamingMessage);
            boolean hasContent = streamingMessage.content != null && !streamingMessage.content.trim().isEmpty();
            boolean hasReasoning = streamingMessage.reasoning != null && !streamingMessage.reasoning.trim().isEmpty();
            if (!hasContent && !hasReasoning) {
                allMessages.remove(streamingMessage);
            } else {
                persistSessionMessagesAsync();
                if (reportAssistantToMemory && hasContent) {
                    reportCharacterInteractionAsync(CharacterMemoryApi.ROLE_ASSISTANT, streamingMessage.content);
                }
            }
        } else {
            stopStreamTypewriter(true);
        }
        flushStreamRenderNow();
        maybeAutoScrollToBottom(shouldStickBottomAfterDone);
    }

    private void scheduleStreamRender() {
        long throttle = pendingStreamChars.length() >= STREAM_RENDER_BUSY_PENDING_CHARS
                ? STREAM_RENDER_THROTTLE_BUSY_MS
                : STREAM_RENDER_THROTTLE_MS;
        long now = System.currentTimeMillis();
        long wait = Math.max(0L, throttle - (now - lastStreamRenderAt));
        if (streamRenderPending) return;
        streamRenderPending = true;
        mainHandler.postDelayed(streamRenderRunnable, wait);
    }

    private void flushStreamRenderNow() {
        mainHandler.removeCallbacks(streamRenderRunnable);
        streamRenderPending = false;
        lastStreamRenderAt = System.currentTimeMillis();
        applyMessagesAndTitle();
    }

    private void enqueueStreamDelta(Message message, String delta) {
        if (message == null || delta == null || delta.isEmpty()) return;
        if (streamingTargetMessage != message) {
            streamingTargetMessage = message;
            pendingStreamChars.setLength(0);
        }
        pendingStreamChars.append(delta);
        if (streamTypewriterRunning) return;
        streamTypewriterRunning = true;
        mainHandler.post(streamTypewriterRunnable);
    }

    private void stopStreamTypewriter(boolean clearPending) {
        mainHandler.removeCallbacks(streamTypewriterRunnable);
        streamTypewriterRunning = false;
        if (clearPending) pendingStreamChars.setLength(0);
    }

    private void drainPendingStreamCharsTo(Message message) {
        if (message == null) {
            stopStreamTypewriter(true);
            return;
        }
        mainHandler.removeCallbacks(streamTypewriterRunnable);
        streamTypewriterRunning = false;
        if (pendingStreamChars.length() > 0) {
            String old = message.content != null ? message.content : "";
            message.content = old + pendingStreamChars.toString();
            pendingStreamChars.setLength(0);
        }
    }

    private void renderStreamingMessageTick(Message message) {
        if (isFinishing() || isDestroyed()) return;
        boolean updated = false;
        if (message != null) {
            updated |= historyAdapter.notifyMessageChanged(message);
            updated |= currentAdapter.notifyMessageChanged(message);
        }
        if (!updated) {
            applyMessagesAndTitle();
            return;
        }
        maybeAutoScrollOnStreamTick();
    }

    private void maybeAutoScrollOnStreamTick() {
        long now = System.currentTimeMillis();
        if (now - lastStreamAutoScrollAt < STREAM_AUTO_SCROLL_THROTTLE_MS) return;
        lastStreamAutoScrollAt = now;
        maybeAutoScrollToBottom(false);
    }

    private void maybeAutoScrollToBottom(boolean force) {
        if (scrollMessagesView == null) return;
        if (!force) {
            if (!assistantResponseInProgress) return;
            if (!autoScrollToBottomEnabled) return;
        }
        scrollMessagesView.post(() -> {
            if (isFinishing() || isDestroyed() || scrollMessagesView == null) return;
            View child = scrollMessagesView.getChildAt(0);
            if (child == null) return;
            int y = Math.max(0, child.getMeasuredHeight() - scrollMessagesView.getHeight());
            if (force) {
                scrollMessagesView.scrollTo(0, y);
            } else {
                scrollMessagesView.smoothScrollTo(0, y);
            }
            updateAutoScrollStateFromPosition();
            updateLoadEarlierEntryVisibility();
        });
    }

    private void updateAutoScrollStateFromPosition() {
        if (scrollMessagesView == null) return;
        View child = scrollMessagesView.getChildAt(0);
        if (child == null) {
            autoScrollToBottomEnabled = true;
            return;
        }
        int distanceToBottom = child.getBottom() - (scrollMessagesView.getScrollY() + scrollMessagesView.getHeight());
        int thresholdPx = (int) (AUTO_SCROLL_BOTTOM_GAP_DP * getResources().getDisplayMetrics().density);
        autoScrollToBottomEnabled = distanceToBottom <= thresholdPx;
    }

    private void beginThinking(Message message) {
        if (message == null) return;
        if (!message.thinkingRunning) {
            message.thinkingRunning = true;
            message.thinkingStartedAt = System.currentTimeMillis();
            message.thinkingElapsedMs = 0L;
        }
        activeThinkingMessage = message;
        mainHandler.removeCallbacks(thinkingTicker);
        mainHandler.post(thinkingTicker);
    }

    private void finishThinking(Message message) {
        if (message == null || !message.thinkingRunning) return;
        message.thinkingElapsedMs = Math.max(0L, System.currentTimeMillis() - message.thinkingStartedAt);
        message.thinkingRunning = false;
        if (activeThinkingMessage == message) {
            mainHandler.removeCallbacks(thinkingTicker);
            activeThinkingMessage = null;
        }
    }

    private Message findLatestByRole(int role) {
        for (int i = allMessages.size() - 1; i >= 0; i--) {
            Message m = allMessages.get(i);
            if (m != null && m.role == role) return m;
        }
        return null;
    }

    private void loadOlderMessages() {
        if (loadingOlderMessages || !hasMoreOlderMessages) return;
        if (oldestLoadedCreatedAt == Long.MAX_VALUE || oldestLoadedMessageId == Long.MAX_VALUE) return;
        loadingOlderMessages = true;
        updateLoadEarlierEntryVisibility();
        final long beforeCreatedAt = oldestLoadedCreatedAt;
        final long beforeMessageId = oldestLoadedMessageId;
        executor.execute(() -> {
            List<Message> olderAsc = new ArrayList<>();
            long newOldest = beforeCreatedAt;
            long newOldestMessageId = beforeMessageId;
            int remaining = 0;
            try {
                List<Message> olderDesc = db.messageDao().getOlderBySession(
                        sessionId, beforeCreatedAt, beforeMessageId, LOAD_MORE_BATCH_SIZE);
                olderAsc = toAscending(olderDesc);
                if (!olderAsc.isEmpty()) {
                    Message oldest = olderAsc.get(0);
                    newOldest = oldest.createdAt;
                    newOldestMessageId = oldest.id;
                    remaining = db.messageDao().countOlderMessages(sessionId, newOldest, newOldestMessageId);
                }
            } catch (Exception ignored) {}
            final List<Message> finalOlderAsc = olderAsc;
            final long finalNewOldest = newOldest;
            final long finalNewOldestMessageId = newOldestMessageId;
            final int finalRemaining = remaining;
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!finalOlderAsc.isEmpty()) {
                    allMessages.addAll(0, finalOlderAsc);
                    oldestLoadedCreatedAt = finalNewOldest;
                    oldestLoadedMessageId = finalNewOldestMessageId;
                    olderRemainingCount = Math.max(0, finalRemaining);
                    hasMoreOlderMessages = olderRemainingCount > 0;
                    applyMessagesAndTitle();
                } else {
                    hasMoreOlderMessages = false;
                    olderRemainingCount = 0;
                }
                loadingOlderMessages = false;
                updateLoadEarlierEntryVisibility();
            });
        });
    }

    private void updateLoadEarlierEntryVisibility() {
        if (loadEarlierMessagesView == null) return;
        boolean atTop = isAtTopForLoadMore();
        boolean visible = hasMoreOlderMessages && atTop;
        loadEarlierMessagesView.setVisibility(visible ? View.VISIBLE : View.GONE);
        loadEarlierMessagesView.setEnabled(!loadingOlderMessages);
        if (loadingOlderMessages) {
            loadEarlierMessagesView.setText(getString(R.string.loading_earlier_messages));
        } else if (olderRemainingCount > 0) {
            loadEarlierMessagesView.setText(getString(R.string.load_earlier_messages_remaining, olderRemainingCount));
        } else {
            loadEarlierMessagesView.setText(getString(R.string.load_earlier_messages));
        }
    }

    private boolean isAtTopForLoadMore() {
        if (scrollMessagesView == null) return true;
        int gapPx = (int) (TOP_LOAD_TRIGGER_GAP_DP * getResources().getDisplayMetrics().density);
        return scrollMessagesView.getScrollY() <= gapPx;
    }

    private List<Message> toAscending(List<Message> descList) {
        List<Message> out = new ArrayList<>();
        if (descList == null || descList.isEmpty()) return out;
        out.addAll(descList);
        java.util.Collections.reverse(out);
        return out;
    }
}
