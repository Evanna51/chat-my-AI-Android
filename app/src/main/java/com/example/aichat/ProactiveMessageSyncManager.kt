package com.example.aichat

import android.content.Context
import android.util.Log
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ProactiveMessageSyncManager(context: Context) {

    companion object {
        private const val TAG = "ProactiveMessageSync"
        private const val DEFAULT_PULL_LIMIT = 20
        private val SYNC_RUNNING = AtomicBoolean(false)
    }

    interface SyncCallback {
        fun onSessionUpdated(sessionId: String) {}
        fun onComplete() {}
    }

    private val appContext: Context = context.applicationContext
    private val memoryService: CharacterMemoryService = CharacterMemoryService(appContext)
    private val syncStore: ProactiveMessageSyncStore = ProactiveMessageSyncStore(appContext)
    private val notifier: ProactiveMessageNotifier = ProactiveMessageNotifier(appContext)
    private val bindingStore: SessionAssistantBindingStore = SessionAssistantBindingStore(appContext)
    private val assistantStore: MyAssistantStore = MyAssistantStore(appContext)
    private val db: AppDatabase = AppDatabase.getInstance(appContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun syncOnce(callback: SyncCallback?) {
        if (!SYNC_RUNNING.compareAndSet(false, true)) {
            callback?.onComplete()
            return
        }
        executor.execute {
            try {
                doSync(callback)
            } finally {
                SYNC_RUNNING.set(false)
                callback?.onComplete()
            }
        }
    }

    private fun doSync(callback: SyncCallback?) {
        if (!memoryService.isEnabled()) return
        val since = syncStore.getLastSince()
        val response: CharacterMemoryApi.PullMessagesResponse?
        try {
            response = memoryService.pullMessages(since, DEFAULT_PULL_LIMIT)
        } catch (e: Exception) {
            Log.w(TAG, "pull failed: " + (e.message ?: ""))
            return
        }
        if (response == null || !response.ok || response.messages == null || response.messages.isEmpty()) {
            if (response != null && response.now != null && response.now.trim().isNotEmpty()) {
                syncStore.setLastSince(response.now.trim())
            }
            return
        }

        val touchedSessions: MutableList<String> = ArrayList()
        for (item in response.messages) {
            if (item == null) continue
            val messageId = safeTrim(item.id)
            if (messageId.isEmpty()) continue
            if (!isEligibleAssistant(item.assistantId)) {
                tryAck(messageId, "failed")
                syncStore.markProcessed(messageId)
                continue
            }
            if (syncStore.isRecentlyProcessed(messageId)) {
                tryAck(messageId, "displayed")
                continue
            }

            val targetSessionId = resolveTargetSessionId(item)
            val notified = notifier.notifyMessage(
                messageId,
                item.title,
                item.body,
                targetSessionId,
                item.assistantId
            )

            val ackStatus: String
            if (targetSessionId.isEmpty()) {
                ackStatus = "ignored_no_session"
            } else {
                val text = firstNonEmpty(item.body, item.title)
                if (text.isEmpty()) {
                    ackStatus = "failed"
                } else {
                    ackStatus = try {
                        val message = Message(targetSessionId, Message.ROLE_ASSISTANT, text)
                        val ts = parseServerTime(item.createdAt)
                        if (ts > 0L) message.createdAt = ts
                        db.messageDao().insert(message)
                        touchedSessions.add(targetSessionId)
                        "inserted"
                    } catch (e: Exception) {
                        "failed"
                    }
                }
            }
            val finalAckStatus = if ("failed" == ackStatus && notified) "displayed" else ackStatus
            tryAck(messageId, finalAckStatus)
            syncStore.markProcessed(messageId)
        }
        if (response.now != null && response.now.trim().isNotEmpty()) {
            syncStore.setLastSince(response.now.trim())
        }
        if (callback != null) {
            for (one in touchedSessions) {
                callback.onSessionUpdated(one)
            }
        }
    }

    private fun isEligibleAssistant(assistantId: String?): Boolean {
        val id = safeTrim(assistantId)
        if (id.isEmpty()) return false
        val assistant = assistantStore.getById(id) ?: return false
        return "character" == assistant.type && assistant.allowProactiveMessage
    }

    private fun resolveTargetSessionId(item: CharacterMemoryApi.PulledMessage): String {
        val direct = safeTrim(item.sessionId)
        if (direct.isNotEmpty()) {
            if (bindingStore.containsSession(direct) || db.messageDao().countBySessionId(direct) > 0) {
                return direct
            }
        }
        val assistantId = safeTrim(item.assistantId)
        if (assistantId.isEmpty()) return ""
        val sessionIds = bindingStore.getSessionIdsByAssistantId(assistantId)
        if (sessionIds.isEmpty()) return ""
        val latestByMessage = db.messageDao().getLatestSessionIdIn(sessionIds)
        if (latestByMessage != null && latestByMessage.trim().isNotEmpty()) {
            return latestByMessage.trim()
        }
        return sessionIds[sessionIds.size - 1]
    }

    private fun tryAck(messageId: String, ackStatus: String) {
        try {
            memoryService.ackMessage(messageId, ackStatus)
        } catch (e: Exception) {
            Log.w(TAG, "ack failed: " + (e.message ?: ""))
        }
    }

    private fun parseServerTime(source: String?): Long {
        val text = safeTrim(source)
        if (text.isEmpty()) return 0L
        try {
            val parsed = text.toLong()
            if (parsed <= 0L) return 0L
            if (parsed < 100000000000L) return parsed * 1000L
            return parsed
        } catch (ignored: Exception) {}
        try {
            return Instant.parse(text).toEpochMilli()
        } catch (ignored: Exception) {}
        return 0L
    }

    private fun firstNonEmpty(a: String?, b: String?): String {
        val one = safeTrim(a)
        if (one.isNotEmpty()) return one
        return safeTrim(b)
    }

    private fun safeTrim(text: String?): String = text?.trim() ?: ""
}
