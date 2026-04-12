package com.example.aichat;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {
                Message.class,
                SessionMetaEntity.class,
                SessionChatOptionsEntity.class,
                MyAssistantEntity.class,
                SessionAssistantBindingEntity.class
        },
        version = 2,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase INSTANCE;

    public abstract MessageDao messageDao();
    public abstract SessionMetaDao sessionMetaDao();
    public abstract SessionChatOptionsDao sessionChatOptionsDao();
    public abstract MyAssistantDao myAssistantDao();
    public abstract SessionAssistantBindingDao sessionAssistantBindingDao();

    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `session_meta` ("
                    + "`sessionId` TEXT NOT NULL PRIMARY KEY, "
                    + "`title` TEXT, "
                    + "`outline` TEXT, "
                    + "`avatar` TEXT, "
                    + "`category` TEXT, "
                    + "`favorite` INTEGER NOT NULL DEFAULT 0, "
                    + "`pinned` INTEGER NOT NULL DEFAULT 0, "
                    + "`hidden` INTEGER NOT NULL DEFAULT 0, "
                    + "`deleted` INTEGER NOT NULL DEFAULT 0)");

            db.execSQL("CREATE TABLE IF NOT EXISTS `session_chat_options` ("
                    + "`sessionId` TEXT NOT NULL PRIMARY KEY, "
                    + "`sessionTitle` TEXT, "
                    + "`sessionAvatar` TEXT, "
                    + "`contextMessageCount` INTEGER NOT NULL DEFAULT 6, "
                    + "`modelKey` TEXT, "
                    + "`systemPrompt` TEXT, "
                    + "`temperature` REAL NOT NULL DEFAULT 0.7, "
                    + "`topP` REAL NOT NULL DEFAULT 1.0, "
                    + "`stop` TEXT, "
                    + "`streamOutput` INTEGER NOT NULL DEFAULT 1, "
                    + "`autoChapterPlan` INTEGER NOT NULL DEFAULT 0, "
                    + "`thinking` INTEGER NOT NULL DEFAULT 0, "
                    + "`googleThinkingBudget` INTEGER NOT NULL DEFAULT 1024)");

            db.execSQL("CREATE TABLE IF NOT EXISTS `my_assistant` ("
                    + "`id` TEXT NOT NULL PRIMARY KEY, "
                    + "`name` TEXT, "
                    + "`prompt` TEXT, "
                    + "`avatar` TEXT, "
                    + "`avatarImageBase64` TEXT, "
                    + "`firstDialogue` TEXT, "
                    + "`type` TEXT, "
                    + "`allowAutoLife` INTEGER NOT NULL DEFAULT 0, "
                    + "`allowProactiveMessage` INTEGER NOT NULL DEFAULT 0, "
                    + "`options_json` TEXT, "
                    + "`updatedAt` INTEGER NOT NULL DEFAULT 0)");

            db.execSQL("CREATE TABLE IF NOT EXISTS `session_assistant_binding` ("
                    + "`sessionId` TEXT NOT NULL PRIMARY KEY, "
                    + "`assistantId` TEXT)");

            db.execSQL("CREATE INDEX IF NOT EXISTS `index_session_assistant_binding_assistantId` "
                    + "ON `session_assistant_binding` (`assistantId`)");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "ai_chat_db"
                    )
                    .addMigrations(MIGRATION_1_2)
                    .allowMainThreadQueries() // 临时：待优化2(ViewModel)完成后移除
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
