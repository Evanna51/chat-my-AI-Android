package com.example.aichat

import android.content.Context
import java.util.LinkedHashSet

class SessionMetaStore(context: Context) {

    private val dao: SessionMetaDao = AppDatabase.getInstance(context).sessionMetaDao()

    fun get(sessionId: String?): SessionMeta {
        if (sessionId.isNullOrEmpty()) return SessionMeta()
        val entity = dao.get(sessionId)
        return if (entity != null) fromEntity(entity) else SessionMeta()
    }

    fun getAll(): Map<String, SessionMeta> {
        val entities = dao.getAll()
        val map = HashMap<String, SessionMeta>()
        if (entities != null) {
            for (e in entities) {
                if (e != null) map[e.sessionId] = fromEntity(e)
            }
        }
        return map
    }

    fun save(sessionId: String?, meta: SessionMeta?) {
        if (sessionId.isNullOrEmpty() || meta == null) return
        dao.upsert(toEntity(sessionId, meta))
    }

    fun remove(sessionId: String?) {
        if (sessionId.isNullOrEmpty()) return
        dao.delete(sessionId)
    }

    fun updateTitle(sessionId: String, title: String?) {
        dao.updateTitle(sessionId, title?.trim() ?: "")
    }

    fun updateCategory(sessionId: String, category: String?) {
        val safe = if (!category?.trim().isNullOrEmpty()) category!!.trim() else "默认"
        dao.updateCategory(sessionId, safe)
    }

    fun setFavorite(sessionId: String, favorite: Boolean) {
        dao.setFavorite(sessionId, favorite)
        if (favorite) dao.updateCategory(sessionId, "收藏")
    }

    fun setPinned(sessionId: String, pinned: Boolean) {
        dao.setPinned(sessionId, pinned)
    }

    fun setDeleted(sessionId: String, deleted: Boolean) {
        dao.setDeleted(sessionId, deleted)
    }

    fun setHidden(sessionId: String, hidden: Boolean) {
        dao.setHidden(sessionId, hidden)
    }

    fun updateOutline(sessionId: String, outline: String?) {
        dao.updateOutline(sessionId, outline?.trim() ?: "")
    }

    fun getAllCategories(): List<String> {
        val categories = LinkedHashSet<String>()
        categories.add("默认")
        categories.add("工作")
        categories.add("生活")
        categories.add("收藏")
        categories.add("学习")
        val fromDb = dao.getAllCategories()
        if (fromDb != null) categories.addAll(fromDb)
        return ArrayList(categories)
    }

    // --- Conversion helpers ---

    companion object {
        private fun toEntity(sessionId: String, meta: SessionMeta): SessionMetaEntity {
            val entity = SessionMetaEntity()
            entity.sessionId = sessionId
            entity.title = meta.title ?: ""
            entity.outline = meta.outline ?: ""
            entity.avatar = meta.avatar ?: ""
            entity.category = if (!meta.category?.trim().isNullOrEmpty()) meta.category!!.trim() else "默认"
            entity.favorite = meta.favorite
            entity.pinned = meta.pinned
            entity.hidden = meta.hidden
            entity.deleted = meta.deleted
            return entity
        }

        private fun fromEntity(entity: SessionMetaEntity): SessionMeta {
            val meta = SessionMeta()
            meta.title = entity.title ?: ""
            meta.outline = entity.outline ?: ""
            meta.avatar = entity.avatar ?: ""
            meta.category = if (!entity.category?.trim().isNullOrEmpty()) entity.category!!.trim() else "默认"
            meta.favorite = entity.favorite
            meta.pinned = entity.pinned
            meta.hidden = entity.hidden
            meta.deleted = entity.deleted
            return meta
        }
    }
}
