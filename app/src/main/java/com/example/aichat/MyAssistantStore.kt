package com.example.aichat

import android.content.Context
import com.google.gson.Gson
import java.util.UUID

class MyAssistantStore(context: Context) {

    private val dao: MyAssistantDao = AppDatabase.getInstance(context).myAssistantDao()

    fun getAll(): List<MyAssistant> {
        val entities = dao.getAll()
        val result = ArrayList<MyAssistant>()
        if (entities != null) {
            for (entity in entities) {
                if (entity != null) result.add(fromEntity(entity))
            }
        }
        return result
    }

    fun getRecent(n: Int): List<MyAssistant> {
        val all = getAll()
        if (all.size <= n) return all
        return ArrayList(all.subList(0, n))
    }

    fun getById(id: String?): MyAssistant? {
        if (id.isNullOrEmpty()) return null
        val entity = dao.getById(id)
        return if (entity != null) fromEntity(entity) else null
    }

    fun createEmpty(): MyAssistant {
        val a = MyAssistant()
        a.id = UUID.randomUUID().toString()
        a.name = "新助手"
        a.prompt = ""
        a.avatar = ""
        a.avatarImageBase64 = ""
        a.firstDialogue = ""
        a.type = "default"
        a.allowAutoLife = false
        a.allowProactiveMessage = false
        a.options = SessionChatOptions()
        a.updatedAt = System.currentTimeMillis()
        return a
    }

    fun save(assistant: MyAssistant?) {
        if (assistant == null || assistant.id.isNullOrEmpty()) return
        assistant.updatedAt = System.currentTimeMillis()
        dao.upsert(toEntity(assistant))
    }

    fun delete(id: String?) {
        if (id.isNullOrEmpty()) return
        dao.delete(id)
    }

    // --- Conversion helpers ---

    companion object {
        private val GSON = Gson()

        private fun toEntity(a: MyAssistant): MyAssistantEntity {
            val entity = MyAssistantEntity()
            entity.id = a.id
            entity.name = a.name
            entity.prompt = "" // always clear legacy field on write
            entity.avatar = a.avatar
            entity.avatarImageBase64 = a.avatarImageBase64
            entity.firstDialogue = a.firstDialogue
            entity.type = a.type
            entity.allowAutoLife = a.allowAutoLife
            entity.allowProactiveMessage = a.allowProactiveMessage
            entity.optionsJson = GSON.toJson(a.options ?: SessionChatOptions())
            entity.updatedAt = a.updatedAt
            return entity
        }

        private fun fromEntity(entity: MyAssistantEntity): MyAssistant {
            val a = MyAssistant()
            a.id = entity.id
            a.name = entity.name ?: ""
            a.prompt = "" // legacy field; always empty after migration
            a.avatar = entity.avatar ?: ""
            a.avatarImageBase64 = entity.avatarImageBase64 ?: ""
            a.firstDialogue = entity.firstDialogue ?: ""
            a.type = entity.type ?: ""
            a.allowAutoLife = entity.allowAutoLife
            a.allowProactiveMessage = entity.allowProactiveMessage
            a.updatedAt = entity.updatedAt
            // Deserialize options from JSON
            if (!entity.optionsJson.isNullOrEmpty()) {
                try {
                    a.options = GSON.fromJson(entity.optionsJson, SessionChatOptions::class.java)
                } catch (ignored: Exception) {}
            }
            if (a.options == null) a.options = SessionChatOptions()
            return a
        }
    }
}
