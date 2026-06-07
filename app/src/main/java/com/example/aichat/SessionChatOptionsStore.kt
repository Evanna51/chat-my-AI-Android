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
            entity.sessionAvatarImageBase64 = opts.sessionAvatarImageBase64
            entity.modelKey = opts.modelKey ?: ""
            entity.systemPrompt = opts.systemPrompt ?: ""
            entity.stop = opts.stop ?: ""
            entity.contextMessageCount = opts.contextMessageCount
            entity.googleThinkingBudget = opts.googleThinkingBudget
            entity.temperature = opts.temperature
            entity.topP = opts.topP
            entity.maxTokens = opts.maxTokens
            entity.frequencyPenalty = opts.frequencyPenalty
            entity.presencePenalty = opts.presencePenalty
            entity.topK = opts.topK
            entity.streamOutput = true
            entity.autoChapterPlan = opts.autoChapterPlan
            entity.thinking = opts.thinking
            entity.autoChatEnabled = opts.autoChatEnabled
            entity.proactiveCountToday = opts.proactiveCountToday
            entity.proactiveResetDate = opts.proactiveResetDate
            entity.proactiveDailyBudget = opts.proactiveDailyBudget
            entity.inkosEnabled = opts.inkosEnabled
            entity.inkosBookId = opts.inkosBookId
            entity.inkosSubtype = opts.inkosSubtype
            entity.inkosBookRulesYaml = opts.inkosBookRulesYaml
            entity.inkosTargetChapters = opts.inkosTargetChapters
            entity.inkosChapterWordCount = opts.inkosChapterWordCount
            return entity
        }

        private fun fromEntity(entity: SessionChatOptionsEntity): SessionChatOptions {
            val opts = SessionChatOptions()
            opts.sessionTitle = entity.sessionTitle ?: ""
            opts.sessionAvatar = entity.sessionAvatar ?: ""
            opts.sessionAvatarImageBase64 = entity.sessionAvatarImageBase64
            opts.modelKey = entity.modelKey ?: ""
            opts.systemPrompt = entity.systemPrompt ?: ""
            opts.stop = entity.stop ?: ""
            opts.contextMessageCount = entity.contextMessageCount
            opts.googleThinkingBudget = entity.googleThinkingBudget
            opts.temperature = entity.temperature
            opts.topP = entity.topP
            opts.maxTokens = entity.maxTokens
            opts.frequencyPenalty = entity.frequencyPenalty
            opts.presencePenalty = entity.presencePenalty
            opts.topK = entity.topK
            opts.streamOutput = true
            opts.autoChapterPlan = entity.autoChapterPlan
            opts.thinking = entity.thinking
            opts.autoChatEnabled = entity.autoChatEnabled
            opts.proactiveCountToday = entity.proactiveCountToday
            opts.proactiveResetDate = entity.proactiveResetDate
            opts.proactiveDailyBudget = entity.proactiveDailyBudget
            opts.inkosEnabled = entity.inkosEnabled
            opts.inkosBookId = entity.inkosBookId
            opts.inkosSubtype = entity.inkosSubtype
            opts.inkosBookRulesYaml = entity.inkosBookRulesYaml
            opts.inkosTargetChapters = entity.inkosTargetChapters
            opts.inkosChapterWordCount = entity.inkosChapterWordCount
            return opts
        }
    }
}
