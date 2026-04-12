package com.example.aichat;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "session_chat_options")
public class SessionChatOptionsEntity {
    @PrimaryKey
    @NonNull
    public String sessionId = "";

    public String sessionTitle = "";
    public String sessionAvatar = "";
    public String modelKey = "";
    public String systemPrompt = "";
    public String stop = "";

    @ColumnInfo(defaultValue = "6")
    public int contextMessageCount = 6;

    @ColumnInfo(defaultValue = "1024")
    public int googleThinkingBudget = 1024;

    @ColumnInfo(defaultValue = "0.7")
    public float temperature = 0.7f;

    @ColumnInfo(defaultValue = "1.0")
    public float topP = 1.0f;

    @ColumnInfo(defaultValue = "1")
    public boolean streamOutput = true;

    @ColumnInfo(defaultValue = "0")
    public boolean autoChapterPlan;

    @ColumnInfo(defaultValue = "0")
    public boolean thinking;
}
