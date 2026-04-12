package com.example.aichat

import android.content.Context

class SessionChatOptionsStore(context: Context) {

    private val dao: SessionChatOptionsDao = AppDatabase.getInstance(context).sessionChatOptionsDao()

    fun get(sessionId: String?): SessionChatOptions {
        if (sessionId.isNullOrEmpty()) return defaultOptions()
        val entity = dao.get(sessionId)
        val out = if (entity != null) fromEntity(entity) else SessionChatOptions()
        out.streamOutput = true // always force true per existing contract
        return out
    }

    fun has(sessionId: String?): Boolean {
        if (sessionId.isNullOrEmpty()) return false
        return dao.has(sessionId) > 0
    }

    fun save(sessionId: String?, options: SessionChatOptions?) {
        if (sessionId.isNullOrEmpty() || options == null) return
        options.streamOutput = true
        dao.upsert(toEntity(sessionId, options))
    }

    fun remove(sessionId: String?) {
        if (sessionId.isNullOrEmpty()) return
        dao.delete(sessionId)
    }

    // --- Conversion helpers ---

    companion object {
        private fun defaultOptions(): SessionChatOptions {
            val opts = SessionChatOptions()
            opts.streamOutput = true
            return opts
        }

        private fun toEntity(sessionId: String, opts: SessionChatOptions): SessionChatOptionsEntity {
            val entity = SessionChatOptionsEntity()
            entity.sessionId = sessionId
            entity.sessionTitle = opts.sessionTitle ?: ""
            entity.sessionAvatar = opts.sessionAvatar ?: ""
            entity.modelKey = opts.modelKey ?: ""
            entity.systemPrompt = opts.systemPrompt ?: ""
            entity.stop = opts.stop ?: ""
            entity.contextMessageCount = opts.contextMessageCount
            entity.googleThinkingBudget = opts.googleThinkingBudget
            entity.temperature = opts.temperature
            entity.topP = opts.topP
            entity.streamOutput = true
            entity.autoChapterPlan = opts.autoChapterPlan
            entity.thinking = opts.thinking
            return entity
        }

        private fun fromEntity(entity: SessionChatOptionsEntity): SessionChatOptions {
            val opts = SessionChatOptions()
            opts.sessionTitle = entity.sessionTitle ?: ""
            opts.sessionAvatar = entity.sessionAvatar ?: ""
            opts.modelKey = entity.modelKey ?: ""
            opts.systemPrompt = entity.systemPrompt ?: ""
            opts.stop = entity.stop ?: ""
            opts.contextMessageCount = entity.contextMessageCount
            opts.googleThinkingBudget = entity.googleThinkingBudget
            opts.temperature = entity.temperature
            opts.topP = entity.topP
            opts.streamOutput = true
            opts.autoChapterPlan = entity.autoChapterPlan
            opts.thinking = entity.thinking
            return opts
        }
    }
}
