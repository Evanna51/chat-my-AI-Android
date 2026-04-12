package com.example.aichat;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MyAssistantDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MyAssistantEntity entity);

    @Query("SELECT * FROM my_assistant ORDER BY updatedAt DESC")
    List<MyAssistantEntity> getAll();

    @Query("SELECT * FROM my_assistant WHERE id = :id")
    MyAssistantEntity getById(String id);

    @Query("DELETE FROM my_assistant WHERE id = :id")
    void delete(String id);
}
