package com.example.aichat;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "my_assistant")
public class MyAssistantEntity {
    @PrimaryKey
    @NonNull
    public String id = "";

    public String name;
    /** @deprecated Legacy field; use options_json → systemPrompt instead. Kept for migration. */
    @Deprecated
    public String prompt;
    public String avatar;
    public String avatarImageBase64;
    public String firstDialogue;
    public String type;

    @ColumnInfo(defaultValue = "0")
    public boolean allowAutoLife;

    @ColumnInfo(defaultValue = "0")
    public boolean allowProactiveMessage;

    /** Gson-serialized SessionChatOptions JSON string. */
    @ColumnInfo(name = "options_json")
    public String optionsJson;

    @ColumnInfo(defaultValue = "0")
    public long updatedAt;
}
