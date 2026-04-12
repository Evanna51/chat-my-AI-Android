package com.example.aichat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MyAssistantDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: MyAssistantEntity)

    @Query("SELECT * FROM my_assistant ORDER BY updatedAt DESC")
    fun getAll(): List<MyAssistantEntity>

    @Query("SELECT * FROM my_assistant WHERE id = :id")
    fun getById(id: String): MyAssistantEntity?

    @Query("DELETE FROM my_assistant WHERE id = :id")
    fun delete(id: String)
}
