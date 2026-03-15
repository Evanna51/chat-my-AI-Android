package com.example.aichat;

public class SessionChatOptions {
    public String sessionTitle = "";
    public String sessionAvatar = "";
    public int contextMessageCount = 6;
    public String modelKey = "";
    public String systemPrompt = "";
    public float temperature = 0.7f;
    public float topP = 1.0f;
    public String stop = "";
    public boolean streamOutput = true;
    public boolean autoChapterPlan = false;
    public boolean thinking = false;
    public int googleThinkingBudget = 1024;
}

