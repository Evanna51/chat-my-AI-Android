package com.example.aichat;

import android.content.Context;
import android.util.Log;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ProactiveMessageSyncManager {
    private static final String TAG = "ProactiveMessageSync";
    private static final int DEFAULT_PULL_LIMIT = 20;
    private static final AtomicBoolean SYNC_RUNNING = new AtomicBoolean(false);

    public interface SyncCallback {
        default void onSessionUpdated(String sessionId) {}
        default void onComplete() {}
    }

    private final Context appContext;
    private final CharacterMemoryService memoryService;
    private final ProactiveMessageSyncStore syncStore;
    private final ProactiveMessageNotifier notifier;
    private final SessionAssistantBindingStore bindingStore;
    private final MyAssistantStore assistantStore;
    private final AppDatabase db;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public ProactiveMessageSyncManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.memoryService = new CharacterMemoryService(appContext);
        this.syncStore = new ProactiveMessageSyncStore(appContext);
        this.notifier = new ProactiveMessageNotifier(appContext);
        this.bindingStore = new SessionAssistantBindingStore(appContext);
        this.assistantStore = new MyAssistantStore(appContext);
        this.db = AppDatabase.getInstance(appContext);
    }

    public void syncOnce(SyncCallback callback) {
        if (!SYNC_RUNNING.compareAndSet(false, true)) {
            if (callback != null) callback.onComplete();
            return;
        }
        executor.execute(() -> {
            try {
                doSync(callback);
            } finally {
                SYNC_RUNNING.set(false);
                if (callback != null) callback.onComplete();
            }
        });
    }

    private void doSync(SyncCallback callback) {
        if (!memoryService.isEnabled()) return;
        String since = syncStore.getLastSince();
        CharacterMemoryApi.PullMessagesResponse response;
        try {
            response = memoryService.pullMessages(since, DEFAULT_PULL_LIMIT);
        } catch (Exception e) {
            Log.w(TAG, "pull failed: " + (e != null ? e.getMessage() : ""));
            return;
        }
        if (response == null || !response.ok || response.messages == null || response.messages.isEmpty()) {
            if (response != null && response.now != null && !response.now.trim().isEmpty()) {
                syncStore.setLastSince(response.now.trim());
            }
            return;
        }

        List<String> touchedSessions = new ArrayList<>();
        for (CharacterMemoryApi.PulledMessage item : response.messages) {
            if (item == null) continue;
            String messageId = safeTrim(item.id);
            if (messageId.isEmpty()) continue;
            if (!isEligibleAssistant(item.assistantId)) {
                tryAck(messageId, "failed");
                syncStore.markProcessed(messageId);
                continue;
            }
            if (syncStore.isRecentlyProcessed(messageId)) {
                tryAck(messageId, "displayed");
                continue;
            }

            String targetSessionId = resolveTargetSessionId(item);
            boolean notified = notifier.notifyMessage(
                    messageId,
                    item.title,
                    item.body,
                    targetSessionId,
                    item.assistantId
            );

            String ackStatus;
            if (targetSessionId.isEmpty()) {
                ackStatus = "ignored_no_session";
            } else {
                String text = firstNonEmpty(item.body, item.title);
                if (text.isEmpty()) {
                    ackStatus = "failed";
                } else {
                    try {
                        Message message = new Message(targetSessionId, Message.ROLE_ASSISTANT, text);
                        long ts = parseServerTime(item.createdAt);
                        if (ts > 0L) message.createdAt = ts;
                        db.messageDao().insert(message);
                        touchedSessions.add(targetSessionId);
                        ackStatus = "inserted";
                    } catch (Exception e) {
                        ackStatus = "failed";
                    }
                }
            }
            if ("failed".equals(ackStatus) && notified) {
                ackStatus = "displayed";
            }
            tryAck(messageId, ackStatus);
            syncStore.markProcessed(messageId);
        }
        if (response.now != null && !response.now.trim().isEmpty()) {
            syncStore.setLastSince(response.now.trim());
        }
        if (callback != null) {
            for (String one : touchedSessions) {
                callback.onSessionUpdated(one);
            }
        }
    }

    private boolean isEligibleAssistant(String assistantId) {
        String id = safeTrim(assistantId);
        if (id.isEmpty()) return false;
        MyAssistant assistant = assistantStore.getById(id);
        if (assistant == null) return false;
        return "character".equals(assistant.type) && assistant.allowProactiveMessage;
    }

    private String resolveTargetSessionId(CharacterMemoryApi.PulledMessage item) {
        String direct = safeTrim(item.sessionId);
        if (!direct.isEmpty()) {
            if (bindingStore.containsSession(direct) || db.messageDao().countBySessionId(direct) > 0) {
                return direct;
            }
        }
        String assistantId = safeTrim(item.assistantId);
        if (assistantId.isEmpty()) return "";
        List<String> sessionIds = bindingStore.getSessionIdsByAssistantId(assistantId);
        if (sessionIds.isEmpty()) return "";
        String latestByMessage = db.messageDao().getLatestSessionIdIn(sessionIds);
        if (latestByMessage != null && !latestByMessage.trim().isEmpty()) {
            return latestByMessage.trim();
        }
        return sessionIds.get(sessionIds.size() - 1);
    }

    private void tryAck(String messageId, String ackStatus) {
        try {
            memoryService.ackMessage(messageId, ackStatus);
        } catch (Exception e) {
            Log.w(TAG, "ack failed: " + (e != null ? e.getMessage() : ""));
        }
    }

    private long parseServerTime(String source) {
        String text = safeTrim(source);
        if (text.isEmpty()) return 0L;
        try {
            long parsed = Long.parseLong(text);
            if (parsed <= 0L) return 0L;
            if (parsed < 100000000000L) return parsed * 1000L;
            return parsed;
        } catch (Exception ignored) {}
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {}
        return 0L;
    }

    private String firstNonEmpty(String a, String b) {
        String one = safeTrim(a);
        if (!one.isEmpty()) return one;
        return safeTrim(b);
    }

    private String safeTrim(String text) {
        return text != null ? text.trim() : "";
    }
}
