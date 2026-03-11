package com.example.aichat;

import androidx.room.Ignore;

public class SessionSummary {
    public String sessionId;
    public String title;
    @Ignore
    public String avatar;
    @Ignore
    public String category;
    @Ignore
    public boolean favorite;
    @Ignore
    public boolean pinned;
    @Ignore
    public boolean hidden;
    @Ignore
    public boolean deleted;
    @Ignore
    public String outline;
    public long lastAt;

    public SessionSummary() {}

    @Ignore
    public SessionSummary(String sessionId, String title, long lastAt) {
        this.sessionId = sessionId;
        this.title = title;
        this.lastAt = lastAt;
    }
}
