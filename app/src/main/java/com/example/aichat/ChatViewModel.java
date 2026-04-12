package com.example.aichat;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewModel for ChatSessionActivity.
 * <p>
 * Owns: executor, db, chatService, session state (messages, responseToken, chatHandle).
 * Activity owns: streaming UI (typewriter, thinking ticker, scroll), all dialog flows.
 */
public class ChatViewModel extends AndroidViewModel {
    private static final String TAG = "ChatViewModel";
    private static final int INITIAL_RENDER_MESSAGE_LIMIT = 200;
    private static final int LOAD_MORE_BATCH_SIZE = 50;

    // --- LiveData observed by Activity ---
    public final MutableLiveData<List<Message>> messages = new MutableLiveData<>(new ArrayList<>());
    public final MutableLiveData<Boolean> responseInProgress = new MutableLiveData<>(false);
    /** One-shot error event; Activity should consume and show as Toast. */
    public final MutableLiveData<String> errorEvent = new MutableLiveData<>();
    public final MutableLiveData<String> sessionTitle = new MutableLiveData<>();
    public final MutableLiveData<SessionChatOptions> chatOptions = new MutableLiveData<>();
    public final MutableLiveData<Boolean> hasMoreOlderMessages = new MutableLiveData<>(false);
    public final MutableLiveData<Integer> olderRemainingCount = new MutableLiveData<>(0);
    /** Streaming delta events; Activity applies to UI typewriter. */
    public final MutableLiveData<StreamDeltaEvent> streamDeltaEvent = new MutableLiveData<>();

    // --- Internal state ---
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AppDatabase db;
    private final ChatService chatService;

    private String sessionId;
    private volatile long activeResponseToken = 0;
    private volatile ChatService.ChatHandle activeChatHandle;
    private volatile boolean loadingOlderMessages;
    private long oldestLoadedCreatedAt = Long.MAX_VALUE;
    private long oldestLoadedMessageId = Long.MAX_VALUE;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        db = AppDatabase.getInstance(application);
        chatService = new ChatService(application);
    }

    /** Call once from Activity.onCreate; idempotent on config change. */
    public void init(String sessionId) {
        if (this.sessionId != null) return; // already initialized (survived config change)
        this.sessionId = sessionId;
        loadMessages();
    }

    // ─────────────────────────── Message Loading ───────────────────────────

    public void loadMessages() {
        executor.execute(() -> {
            List<Message> list = new ArrayList<>();
            long oldestCreatedAt = Long.MAX_VALUE;
            long oldestMsgId = Long.MAX_VALUE;
            int olderCount = 0;
            try {
                List<Message> desc = db.messageDao().getLatestBySession(sessionId, INITIAL_RENDER_MESSAGE_LIMIT);
                list = toAscending(desc);
                if (!list.isEmpty()) {
                    Message oldest = list.get(0);
                    oldestCreatedAt = oldest.createdAt;
                    oldestMsgId = oldest.id;
                    olderCount = db.messageDao().countOlderMessages(sessionId, oldestCreatedAt, oldestMsgId);
                }
            } catch (Exception e) {
                Log.e(TAG, "loadMessages failed", e);
            }
            oldestLoadedCreatedAt = oldestCreatedAt;
            oldestLoadedMessageId = oldestMsgId;
            final int finalOlderCount = Math.max(0, olderCount);
            loadingOlderMessages = false;
            hasMoreOlderMessages.postValue(finalOlderCount > 0);
            olderRemainingCount.postValue(finalOlderCount);
            messages.postValue(new ArrayList<>(list));
        });
    }

    public void loadOlderMessages() {
        if (loadingOlderMessages) return;
        Boolean moreAvailable = hasMoreOlderMessages.getValue();
        if (moreAvailable == null || !moreAvailable) return;
        if (oldestLoadedCreatedAt == Long.MAX_VALUE || oldestLoadedMessageId == Long.MAX_VALUE) return;
        loadingOlderMessages = true;
        final long beforeCreatedAt = oldestLoadedCreatedAt;
        final long beforeMsgId = oldestLoadedMessageId;
        executor.execute(() -> {
            List<Message> olderAsc = new ArrayList<>();
            long newOldest = beforeCreatedAt;
            long newOldestMsgId = beforeMsgId;
            int remaining = 0;
            try {
                List<Message> olderDesc = db.messageDao().getOlderBySession(
                        sessionId, beforeCreatedAt, beforeMsgId, LOAD_MORE_BATCH_SIZE);
                olderAsc = toAscending(olderDesc);
                if (!olderAsc.isEmpty()) {
                    Message oldest = olderAsc.get(0);
                    newOldest = oldest.createdAt;
                    newOldestMsgId = oldest.id;
                    remaining = db.messageDao().countOlderMessages(sessionId, newOldest, newOldestMsgId);
                }
            } catch (Exception ignored) {}
            final List<Message> finalOlder = olderAsc;
            final long finalNewOldest = newOldest;
            final long finalNewOldestMsgId = newOldestMsgId;
            final int finalRemaining = Math.max(0, remaining);
            if (!finalOlder.isEmpty()) {
                oldestLoadedCreatedAt = finalNewOldest;
                oldestLoadedMessageId = finalNewOldestMsgId;
                // Prepend older messages to current list
                List<Message> current = messages.getValue();
                List<Message> merged = new ArrayList<>(finalOlder);
                if (current != null) merged.addAll(current);
                messages.postValue(merged);
            }
            loadingOlderMessages = false;
            hasMoreOlderMessages.postValue(finalRemaining > 0);
            olderRemainingCount.postValue(finalRemaining);
        });
    }

    // ─────────────────────────── Persistence ───────────────────────────

    public void insertMessageAsync(Message message) {
        executor.execute(() -> {
            try {
                db.messageDao().insert(message);
            } catch (Exception ignored) {}
        });
    }

    public void persistSessionMessagesAsync(List<Message> snapshot) {
        List<Message> copy = new ArrayList<>(snapshot);
        executor.execute(() -> {
            try {
                db.messageDao().deleteBySession(sessionId);
                for (Message m : copy) {
                    if (m == null) continue;
                    Message item = new Message(sessionId, m.role, m.content != null ? m.content : "");
                    item.createdAt = m.createdAt > 0 ? m.createdAt : System.currentTimeMillis();
                    db.messageDao().insert(item);
                }
            } catch (Exception ignored) {}
        });
    }

    public void persistSessionTitle(String title, boolean overwriteExisting) {
        String trimmed = title != null ? title.trim() : "";
        if (trimmed.isEmpty()) return;
        final String finalTitle = trimmed;
        executor.execute(() -> {
            SessionMetaStore metaStore = new SessionMetaStore(getApplication());
            SessionMeta meta = metaStore.get(sessionId);
            String metaTitle = meta.title != null ? meta.title.trim() : "";
            if (overwriteExisting || metaTitle.isEmpty()) {
                meta.title = finalTitle;
                metaStore.save(sessionId, meta);
            }
            SessionChatOptionsStore optionsStore = new SessionChatOptionsStore(getApplication());
            SessionChatOptions options = optionsStore.get(sessionId);
            String optionsTitle = options.sessionTitle != null ? options.sessionTitle.trim() : "";
            if (overwriteExisting || optionsTitle.isEmpty()) {
                options.sessionTitle = finalTitle;
                optionsStore.save(sessionId, options);
            }
        });
    }

    // ─────────────────────────── Chat Options ───────────────────────────

    public SessionChatOptions resolveChatOptions(String assistantId) {
        SessionChatOptionsStore optionsStore = new SessionChatOptionsStore(getApplication());
        SessionChatOptions fromSession = optionsStore.get(sessionId);
        SessionChatOptions opts = fromSession != null ? fromSession : new SessionChatOptions();
        if (optionsStore.has(sessionId)) {
            chatOptions.postValue(opts);
            return opts;
        }
        SessionChatOptions initialized = initializeFromAssistantOrGlobal(opts, assistantId);
        optionsStore.save(sessionId, initialized);
        chatOptions.postValue(initialized);
        return initialized;
    }

    private SessionChatOptions initializeFromAssistantOrGlobal(SessionChatOptions base, String assistantId) {
        if (assistantId != null && !assistantId.trim().isEmpty()) {
            MyAssistant assistant = new MyAssistantStore(getApplication()).getById(assistantId);
            if (assistant != null && assistant.options != null) {
                return assistant.options;
            }
        }
        ConfigManager configManager = new ConfigManager(getApplication());
        SessionChatOptions global = new SessionChatOptions();
        String globalModelKey = configManager.getModel();
        if (globalModelKey != null && !globalModelKey.isEmpty()) {
            global.modelKey = globalModelKey;
        }
        return global;
    }

    // ─────────────────────────── Chat Dispatch ───────────────────────────

    /**
     * @param historyForApi messages to send as context (not including current user message)
     * @param plainApiUserMessage user message text (before memory enrichment)
     * @param options session chat options
     * @param responseToken caller's response token for staleness check
     * @param shouldUseCharacterMemory whether to fetch character memory
     * @param assistantId for character memory calls
     * @param characterMemoryService for memory fetch
     */
    public void dispatchChat(List<Message> historyForApi,
                              String plainApiUserMessage,
                              SessionChatOptions options,
                              long responseToken,
                              boolean shouldUseCharacterMemory,
                              String assistantId,
                              CharacterMemoryService characterMemoryService) {
        activeResponseToken = responseToken;
        if (!shouldUseCharacterMemory) {
            doChatRequest(historyForApi, plainApiUserMessage, options, responseToken, false,
                    assistantId, characterMemoryService);
            return;
        }
        executor.execute(() -> {
            String enriched = plainApiUserMessage;
            try {
                CharacterMemoryApi.MemoryContextResponse memory =
                        characterMemoryService.getMemoryContext(assistantId, sessionId, plainApiUserMessage);
                // enrichment logic is in ChatSessionActivity; for ViewModel we just pass through
                // the memory result is currently handled inline in Activity
            } catch (Exception e) {
                Log.w(TAG, "memory-context failed: " + (e != null ? e.getMessage() : ""));
            }
            final String finalMsg = enriched;
            // Post back to caller (Activity drives the actual dispatch after memory fetch)
            // For now, post a signal event through streamDeltaEvent with isMemoryReady=true
            // This is simplified: Activity's dispatchChatRequestWithOptionalMemory calls viewModel.doChatRequest
        });
    }

    /**
     * Direct chat dispatch (called from Activity after optional memory enrichment).
     */
    public ChatService.ChatHandle doChatRequest(List<Message> historyForApi,
                                                 String apiUserMessage,
                                                 SessionChatOptions options,
                                                 long responseToken,
                                                 boolean reportAssistantToMemory,
                                                 String assistantId,
                                                 CharacterMemoryService characterMemoryService) {
        activeResponseToken = responseToken;
        responseInProgress.postValue(true);
        ChatService.ChatHandle handle = chatService.chat(historyForApi, apiUserMessage, options,
                new ChatService.ChatCallback() {

                    private boolean isStale() {
                        return responseToken != activeResponseToken;
                    }

                    @Override
                    public void onSuccess(String content) {
                        if (isStale()) return;
                        responseInProgress.postValue(false);
                        activeChatHandle = null;
                        String safeContent = content != null ? content : "";
                        // Persist to DB as fallback for when Activity is detached/destroyed
                        executor.execute(() -> {
                            try {
                                db.messageDao().insert(new Message(sessionId, Message.ROLE_ASSISTANT, safeContent));
                            } catch (Exception ignored) {}
                        });
                        StreamDeltaEvent event = new StreamDeltaEvent(responseToken);
                        event.isSuccess = true;
                        event.successContent = safeContent;
                        event.reportAssistantToMemory = reportAssistantToMemory;
                        streamDeltaEvent.postValue(event);
                    }

                    @Override
                    public void onError(String message) {
                        if (isStale()) return;
                        responseInProgress.postValue(false);
                        activeChatHandle = null;
                        errorEvent.postValue(message != null ? message :
                                getApplication().getString(R.string.error_request_failed));
                        StreamDeltaEvent event = new StreamDeltaEvent(responseToken);
                        event.isError = true;
                        streamDeltaEvent.postValue(event);
                    }

                    @Override
                    public void onCancelled() {
                        if (isStale()) return;
                        responseInProgress.postValue(false);
                        activeChatHandle = null;
                        StreamDeltaEvent event = new StreamDeltaEvent(responseToken);
                        event.isCancelled = true;
                        event.reportAssistantToMemory = reportAssistantToMemory;
                        streamDeltaEvent.postValue(event);
                    }

                    @Override
                    public void onPartial(String delta) {
                        if (isStale()) return;
                        StreamDeltaEvent event = new StreamDeltaEvent(responseToken);
                        event.delta = delta;
                        streamDeltaEvent.postValue(event);
                    }

                    @Override
                    public void onReasoning(String reasoning) {
                        if (isStale()) return;
                        StreamDeltaEvent event = new StreamDeltaEvent(responseToken);
                        event.reasoning = reasoning;
                        streamDeltaEvent.postValue(event);
                    }

                    @Override
                    public void onUsage(int promptTokens, int completionTokens, int totalTokens, long elapsedMs) {
                        if (isStale()) return;
                        StreamDeltaEvent event = new StreamDeltaEvent(responseToken);
                        event.isUsage = true;
                        event.promptTokens = promptTokens;
                        event.completionTokens = completionTokens;
                        event.totalTokens = totalTokens;
                        event.elapsedMs = elapsedMs;
                        streamDeltaEvent.postValue(event);
                    }
                });
        activeChatHandle = handle;
        return handle;
    }

    // ─────────────────────────── Thread Title ───────────────────────────

    public void generateThreadTitle(String firstUserMessage, String fallbackTitle) {
        chatService.generateThreadTitle(firstUserMessage, new ChatService.ChatCallback() {
            @Override
            public void onSuccess(String content) {
                String generated = content != null ? content.trim() : "";
                if (generated.isEmpty()) return;
                persistSessionTitle(generated, true);
                sessionTitle.postValue(generated);
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "auto title failed: " + message);
            }
        });
    }

    // ─────────────────────────── Response control ───────────────────────────

    public long incrementResponseToken() {
        activeResponseToken++;
        return activeResponseToken;
    }

    public long getActiveResponseToken() {
        return activeResponseToken;
    }

    public ChatService.ChatHandle getActiveChatHandle() {
        return activeChatHandle;
    }

    public void clearActiveChatHandle() {
        activeChatHandle = null;
    }

    public boolean isLoadingOlderMessages() {
        return loadingOlderMessages;
    }

    // ─────────────────────────── Lifecycle ───────────────────────────

    @Override
    protected void onCleared() {
        executor.shutdown();
        super.onCleared();
    }

    // ─────────────────────────── Helpers ───────────────────────────

    private static List<Message> toAscending(List<Message> descList) {
        List<Message> out = new ArrayList<>();
        if (descList == null || descList.isEmpty()) return out;
        out.addAll(descList);
        Collections.reverse(out);
        return out;
    }

    // ─────────────────────────── StreamDeltaEvent ───────────────────────────

    /** Represents one streaming event from the AI response. */
    public static class StreamDeltaEvent {
        public final long responseToken;

        // Content delta (onPartial)
        public String delta;
        // Reasoning delta (onReasoning)
        public String reasoning;
        // Usage stats (onUsage)
        public boolean isUsage;
        public int promptTokens, completionTokens, totalTokens;
        public long elapsedMs;
        // Terminal events
        public boolean isSuccess;
        public String successContent;
        public boolean reportAssistantToMemory;
        public boolean isError;
        public boolean isCancelled;

        public StreamDeltaEvent(long responseToken) {
            this.responseToken = responseToken;
        }
    }
}
