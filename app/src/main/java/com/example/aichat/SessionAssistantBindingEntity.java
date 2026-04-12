package com.example.aichat;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "session_assistant_binding")
public class SessionAssistantBindingEntity {
    @PrimaryKey
    @NonNull
    public String sessionId = "";

    @ColumnInfo(index = true)
    public String assistantId;
}
