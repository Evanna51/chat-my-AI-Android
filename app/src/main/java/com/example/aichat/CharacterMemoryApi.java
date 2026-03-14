package com.example.aichat;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

public final class CharacterMemoryApi {
    private CharacterMemoryApi() {}

    public static final String PATH_REPORT_INTERACTION = "/api/report-interaction";
    public static final String PATH_MEMORY_CONTEXT = "/api/tool/memory-context";
    public static final String PATH_REPORT_CHARACTER_PROFILE = "/api/assistant-profile/upsert";
    public static final String PATH_PULL_MESSAGES = "/api/pull-messages";
    public static final String PATH_ACK_MESSAGE = "/api/ack-message";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    public static class ReportInteractionRequest {
        public String assistantId;
        public String sessionId;
        public String role;
        public String content;
    }

    public static class MemoryContextRequest {
        public String assistantId;
        public String sessionId;
        // Keep both keys for server compatibility:
        // some deployments expect `userInput`, others may still read `userMessage`.
        public String userInput;
        public String userMessage;
    }

    public static class CharacterProfileRequest {
        public String assistantId;
        public String characterName;
        public String characterBackground;
        public boolean allowAutoLife;
        public boolean allowProactiveMessage;
    }

    public static class MemoryContextResponse {
        public boolean ok;
        public boolean shouldUseMemory;
        public String reason = "";
        public List<String> memoryLines = new ArrayList<>();
        public String memoryGuidance = "";
    }

    public static class PullMessagesResponse {
        public boolean ok;
        public String userId = "";
        public String since = "";
        public int count;
        public List<PulledMessage> messages = new ArrayList<>();
        public String now = "";
    }

    public static class PulledMessage {
        public String id = "";
        public String assistantId = "";
        public String sessionId = "";
        public String messageType = "";
        public String title = "";
        public String body = "";
        public JsonObject payload = new JsonObject();
        public String createdAt = "";
        public String availableAt = "";
        public String expiresAt = "";
        public int pullCount;
    }

    public static class AckMessageRequest {
        public String messageId;
        public String ackStatus;
    }

    public static class AckMessageResponse {
        public boolean ok;
        public String messageId = "";
        public String ackStatus = "";
    }
}
