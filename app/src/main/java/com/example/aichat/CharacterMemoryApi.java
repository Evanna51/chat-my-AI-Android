package com.example.aichat;

import java.util.ArrayList;
import java.util.List;

public final class CharacterMemoryApi {
    private CharacterMemoryApi() {}

    public static final String PATH_REPORT_INTERACTION = "/api/report-interaction";
    public static final String PATH_MEMORY_CONTEXT = "/api/tool/memory-context";
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

    public static class MemoryContextResponse {
        public boolean ok;
        public boolean shouldUseMemory;
        public String reason = "";
        public List<String> memoryLines = new ArrayList<>();
        public String memoryGuidance = "";
    }
}
