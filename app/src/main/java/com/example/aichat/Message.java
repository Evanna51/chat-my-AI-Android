package com.example.aichat;

import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "message")
public class Message {
    public static final int ROLE_USER = 0;
    public static final int ROLE_ASSISTANT = 1;

    @PrimaryKey(autoGenerate = true)
    public long id;
    public String sessionId;
    public int role;
    public String content;
    public long createdAt;
    @Ignore
    public String reasoning;
    @Ignore
    public int promptTokens;
    @Ignore
    public int completionTokens;
    @Ignore
    public int totalTokens;
    @Ignore
    public long elapsedMs;
    @Ignore
    public boolean thinkingRunning;
    @Ignore
    public long thinkingStartedAt;
    @Ignore
    public long thinkingElapsedMs;

    public Message() {}

    @Ignore
    public Message(String sessionId, int role, String content) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.createdAt = System.currentTimeMillis();
    }
}
