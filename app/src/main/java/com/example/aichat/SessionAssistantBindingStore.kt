package com.example.aichat

import android.content.Context

class SessionAssistantBindingStore(context: Context) {

    private val dao: SessionAssistantBindingDao = AppDatabase.getInstance(context).sessionAssistantBindingDao()

    fun bind(sessionId: String?, assistantId: String?) {
        if (sessionId.isNullOrEmpty()) return
        if (assistantId.isNullOrEmpty()) {
            dao.delete(sessionId)
            return
        }
        val entity = SessionAssistantBindingEntity()
        entity.sessionId = sessionId
        entity.assistantId = assistantId
        dao.upsert(entity)
    }

    fun getAssistantId(sessionId: String?): String {
        if (sessionId.isNullOrEmpty()) return ""
        val result = dao.getAssistantId(sessionId)
        return result ?: ""
    }

    fun remove(sessionId: String?) {
        if (sessionId.isNullOrEmpty()) return
        dao.delete(sessionId)
    }

    fun containsSession(sessionId: String?): Boolean {
        if (sessionId?.trim().isNullOrEmpty()) return false
        return dao.contains(sessionId!!.trim()) > 0
    }

    fun getSessionIdsByAssistantId(assistantId: String?): List<String> {
        val target = assistantId?.trim() ?: ""
        if (target.isEmpty()) return ArrayList()
        val result = dao.getSessionIdsByAssistantId(target)
        return result ?: ArrayList()
    }
}
