package com.example.aichat;

import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.widget.NestedScrollView;

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

    public static final String EXTRA_SESSION_ID = "session_id";
    public static final String EXTRA_INITIAL_MESSAGE = "initial_message";
    public static final String EXTRA_ASSISTANT_ID = "assistant_id";
    private static final int CURRENT_THRESHOLD = 10;
    private static final long STREAM_RENDER_THROTTLE_MS = 24L;
    private static final long STREAM_TYPEWRITER_FRAME_MS = 16L;
    private static final int STREAM_TYPEWRITER_CHARS_PER_FRAME = 2;
    private static final int AUTO_SCROLL_BOTTOM_GAP_DP = 64;

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
    private SessionChatOptions sessionOptions = new SessionChatOptions();
    private volatile boolean autoNamingInFlight = false;
    private boolean assistantResponseInProgress;
    private boolean streamRenderPending;
    private long lastStreamRenderAt;
    private ChatService.ChatHandle activeChatHandle;
    private Message activeStreamingMessage;
    private long activeResponseToken;
    private Message streamingTargetMessage;
    private final StringBuilder pendingStreamChars = new StringBuilder();
    private boolean streamTypewriterRunning;
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
            renderStreamingMessageTick(streamingTargetMessage);
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

        chatService = new ChatService(this);
        db = AppDatabase.getInstance(this);
        sessionOptions = resolveChatOptions();

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        View btnSessionSettings = findViewById(R.id.btnSessionSettings);
        if (btnSessionSettings != null) {
            btnSessionSettings.setOnClickListener(v -> startActivity(new Intent(this, SessionChatSettingsActivity.class)
                    .putExtra(SessionChatSettingsActivity.EXTRA_SESSION_ID, sessionId)));
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

        View headerHistory = findViewById(R.id.headerHistory);
        View expandHistory = findViewById(R.id.expandHistory);
        View iconExpand = findViewById(R.id.iconHistoryExpand);

        historyAdapter = new MessageAdapter(assistantMarkdownStateStore);
        currentAdapter = new MessageAdapter(assistantMarkdownStateStore);
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
        }
        if (recyclerCurrent != null) {
            recyclerCurrent.setLayoutManager(new LinearLayoutManager(this));
            recyclerCurrent.setNestedScrollingEnabled(false);
            recyclerCurrent.setAdapter(currentAdapter);
        }
        bindMessageActions(historyAdapter);
        bindMessageActions(currentAdapter);
        setupAutoCollapseActions(recyclerHistory, recyclerCurrent, scrollMessages);

        if (headerHistory != null && expandHistory != null && iconExpand != null) {
            headerHistory.setOnClickListener(v -> {
                historyExpanded = !historyExpanded;
                expandHistory.setVisibility(historyExpanded ? View.VISIBLE : View.GONE);
                iconExpand.setRotation(historyExpanded ? 90 : 0);
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
            try {
                list = db.messageDao().getBySession(sessionId);
            } catch (Exception e) {
                list = new ArrayList<>();
            }
            final List<Message> finalList = list != null ? list : new ArrayList<>();
            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                allMessages = new ArrayList<>(finalList);
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
            });
        });
    }

    private void applyMessagesAndTitle() {
        if (isFinishing() || isDestroyed()) return;
        splitAndDisplay();
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
        historyAdapter.setPinnedActionMessages(latestUser, latestAssistant, assistantResponseInProgress);
        currentAdapter.setPinnedActionMessages(latestUser, latestAssistant, assistantResponseInProgress);

        int total = allMessages.size();
        if (total <= CURRENT_THRESHOLD) {
            cardHistory.setVisibility(View.GONE);
            currentAdapter.setMessages(allMessages);
            historyAdapter.setMessages(new ArrayList<>());
        } else {
            cardHistory.setVisibility(View.VISIBLE);
            if (textHistoryTitle != null) textHistoryTitle.setVisibility(View.VISIBLE);
            int split = total - CURRENT_THRESHOLD;
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
        SessionChatOptions options = resolveChatOptions();
        boolean streamOutput = options != null && options.streamOutput;
        Message streamingAssistant = null;
        if (streamOutput) {
            streamingAssistant = new Message(sessionId, Message.ROLE_ASSISTANT, "");
            streamingAssistant.thinkingRunning = false;
            streamingAssistant.thinkingStartedAt = 0L;
            streamingAssistant.thinkingElapsedMs = 0L;
            allMessages.add(streamingAssistant);
            applyMessagesAndTitle();
            maybeAutoScrollToBottom(true);
        }
        Message finalStreamingAssistant = streamingAssistant;
        activeStreamingMessage = finalStreamingAssistant;
        streamingTargetMessage = finalStreamingAssistant;
        stopStreamTypewriter(true);

        try {
            ChatService.ChatHandle chatHandle = chatService.chat(historyForApi, text, options, new ChatService.ChatCallback() {
                private boolean isStale() {
                    return responseToken != activeResponseToken;
                }

                @Override
                public void onSuccess(String content) {
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (isFinishing() || isDestroyed()) return;
                        boolean shouldStickBottomAfterDone = autoScrollToBottomEnabled;
                        setAssistantResponseInProgress(false);
                        activeChatHandle = null;
                        activeStreamingMessage = null;
                        if (streamOutput && finalStreamingAssistant != null) {
                            finishThinking(finalStreamingAssistant);
                            stopStreamTypewriter(true);
                            finalStreamingAssistant.content = content != null ? content : "";
                            persistSessionMessagesAsync();
                        } else {
                            Message assistantMsg = new Message(sessionId, Message.ROLE_ASSISTANT, content != null ? content : "");
                            executor.execute(() -> {
                                try {
                                    db.messageDao().insert(assistantMsg);
                                } catch (Exception ignored) {}
                            });
                            allMessages.add(assistantMsg);
                        }
                        flushStreamRenderNow();
                        maybeAutoScrollToBottom(shouldStickBottomAfterDone);
                    });
                }

                @Override
                public void onError(String message) {
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        setAssistantResponseInProgress(false);
                        activeChatHandle = null;
                        activeStreamingMessage = null;
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
                        handleResponseStopped(finalStreamingAssistant);
                    });
                }

                @Override
                public void onPartial(String delta) {
                    if (!streamOutput || finalStreamingAssistant == null) return;
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (isFinishing() || isDestroyed()) return;
                        finishThinking(finalStreamingAssistant);
                        enqueueStreamDelta(finalStreamingAssistant, delta);
                    });
                }

                @Override
                public void onReasoning(String reasoning) {
                    if (!streamOutput || finalStreamingAssistant == null) return;
                    mainHandler.post(() -> {
                        if (isStale()) return;
                        if (isFinishing() || isDestroyed()) return;
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
        autoNamingInFlight = true;
        Log.d(TAG, "start auto title generation, sessionId=" + sessionId);
        chatService.generateThreadTitle(firstUserMessage, new ChatService.ChatCallback() {
            @Override
            public void onSuccess(String content) {
                autoNamingInFlight = false;
                Log.d(TAG, "auto title success: " + content);
                if (content == null || content.trim().isEmpty()) return;
                executor.execute(() -> {
                    SessionMetaStore store = new SessionMetaStore(ChatSessionActivity.this);
                    SessionMeta m = store.get(sessionId);
                    if (m.title == null || m.title.trim().isEmpty()) {
                        m.title = content.trim();
                        store.save(sessionId, m);
                    }
                    mainHandler.post(ChatSessionActivity.this::applyMessagesAndTitle);
                });
            }

            @Override
            public void onError(String message) {
                autoNamingInFlight = false;
                Log.e(TAG, "auto title failed: " + message);
            }
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
                // Keep assistant.prompt as prompt fallback for assistants without explicit options prompt.
                if ((out.systemPrompt == null || out.systemPrompt.isEmpty())
                        && assistant.prompt != null && !assistant.prompt.isEmpty()) {
                    out.systemPrompt = assistant.prompt;
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
        out.streamOutput = src.streamOutput;
        out.thinking = src.thinking;
        out.googleThinkingBudget = src.googleThinkingBudget;
        return out;
    }

    @Override
    protected void onDestroy() {
        if (activeChatHandle != null) {
            try {
                activeChatHandle.cancel();
            } catch (Exception ignored) {}
            activeChatHandle = null;
        }
        activeStreamingMessage = null;
        stopStreamTypewriter(true);
        streamingTargetMessage = null;
        mainHandler.removeCallbacks(streamRenderRunnable);
        streamRenderPending = false;
        mainHandler.removeCallbacks(thinkingTicker);
        activeThinkingMessage = null;
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        sessionOptions = resolveChatOptions();
        applyMessagesAndTitle();
    }

    private void updateToolbarModelSubtitle() {
        if (getSupportActionBar() == null) return;
        String modelLabel = "";
        String modelKey = sessionOptions != null ? sessionOptions.modelKey : "";
        ConfiguredModelPicker.Option option = ConfiguredModelPicker.Option.fromStorageKey(modelKey, this);
        if (option != null && option.displayName != null && !option.displayName.trim().isEmpty()) {
            modelLabel = option.displayName.trim();
        } else if (modelKey != null && modelKey.contains(":")) {
            modelLabel = modelKey.substring(modelKey.indexOf(':') + 1).trim();
        }
        if (modelLabel.isEmpty()) {
            getSupportActionBar().setSubtitle(null);
            return;
        }
        getSupportActionBar().setSubtitle(modelLabel);
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
            });
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
        if (handle != null) {
            try {
                handle.cancel();
            } catch (Exception ignored) {}
        }
        handleResponseStopped(target);
    }

    private void handleResponseStopped(Message streamingMessage) {
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
            }
        } else {
            stopStreamTypewriter(true);
        }
        flushStreamRenderNow();
        maybeAutoScrollToBottom(shouldStickBottomAfterDone);
    }

    private void scheduleStreamRender() {
        long now = System.currentTimeMillis();
        long wait = Math.max(0L, STREAM_RENDER_THROTTLE_MS - (now - lastStreamRenderAt));
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
            scrollMessagesView.scrollTo(0, y);
            updateAutoScrollStateFromPosition();
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
}
