package com.example.aichat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RelationshipStateDao {

    @Query("SELECT * FROM relationship_state WHERE assistantId = :assistantId LIMIT 1")
    fun getByAssistant(assistantId: String): RelationshipStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: RelationshipStateEntity)

    @Query("DELETE FROM relationship_state WHERE assistantId = :assistantId")
    fun deleteByAssistant(assistantId: String)

    @Query("DELETE FROM relationship_state")
    fun clearAll()
}
