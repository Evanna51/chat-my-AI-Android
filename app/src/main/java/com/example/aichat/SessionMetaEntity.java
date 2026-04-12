package com.example.aichat;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "session_meta")
public class SessionMetaEntity {
    @PrimaryKey
    @NonNull
    public String sessionId = "";

    public String title = "";
    public String outline = "";
    public String avatar = "";
    public String category = "默认";

    @ColumnInfo(defaultValue = "0")
    public boolean favorite;

    @ColumnInfo(defaultValue = "0")
    public boolean pinned;

    @ColumnInfo(defaultValue = "0")
    public boolean hidden;

    @ColumnInfo(defaultValue = "0")
    public boolean deleted;
}
