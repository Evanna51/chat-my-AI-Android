package com.example.aichat;

public class MyAssistant {
    public String id;
    public String name;
    public String prompt;
    public String avatar;
    public String avatarImageBase64;
    public String type; // default / writer
    public SessionChatOptions options;
    public long updatedAt;

    public MyAssistant() {}
}
