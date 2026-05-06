package com.example.aichat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Message::class,
        SessionMetaEntity::class,
        SessionChatOptionsEntity::class,
        MyAssistantEntity::class,
        SessionAssistantBindingEntity::class,
        RelationshipStateEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun sessionMetaDao(): SessionMetaDao
    abstract fun sessionChatOptionsDao(): SessionChatOptionsDao
    abstract fun myAssistantDao(): MyAssistantDao
    abstract fun sessionAssistantBindingDao(): SessionAssistantBindingDao
    abstract fun relationshipStateDao(): RelationshipStateDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v2 → v3: Kotlin entities declare sessionId/content as TEXT NOT NULL, but the Java-era
         * `message` table (created by Room from nullable Java String fields) has TEXT (nullable).
         * Recreate the table with proper NOT NULL constraints so Room's schema validation passes.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `message_v3` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`sessionId` TEXT NOT NULL, " +
                    "`role` INTEGER NOT NULL, " +
                    "`content` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `message_v3` " +
                    "SELECT `id`, COALESCE(`sessionId`,''), `role`, COALESCE(`content`,''), `createdAt` " +
                    "FROM `message`"
                )
                db.execSQL("DROP TABLE `message`")
                db.execSQL("ALTER TABLE `message_v3` RENAME TO `message`")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_meta` (" +
                    "`sessionId` TEXT NOT NULL PRIMARY KEY, " +
                    "`title` TEXT, " +
                    "`outline` TEXT, " +
                    "`avatar` TEXT, " +
                    "`category` TEXT, " +
                    "`favorite` INTEGER NOT NULL DEFAULT 0, " +
                    "`pinned` INTEGER NOT NULL DEFAULT 0, " +
                    "`hidden` INTEGER NOT NULL DEFAULT 0, " +
                    "`deleted` INTEGER NOT NULL DEFAULT 0)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_chat_options` (" +
                    "`sessionId` TEXT NOT NULL PRIMARY KEY, " +
                    "`sessionTitle` TEXT, " +
                    "`sessionAvatar` TEXT, " +
                    "`contextMessageCount` INTEGER NOT NULL DEFAULT 6, " +
                    "`modelKey` TEXT, " +
                    "`systemPrompt` TEXT, " +
                    "`temperature` REAL NOT NULL DEFAULT 0.7, " +
                    "`topP` REAL NOT NULL DEFAULT 1.0, " +
                    "`stop` TEXT, " +
                    "`streamOutput` INTEGER NOT NULL DEFAULT 1, " +
                    "`autoChapterPlan` INTEGER NOT NULL DEFAULT 0, " +
                    "`thinking` INTEGER NOT NULL DEFAULT 0, " +
                    "`googleThinkingBudget` INTEGER NOT NULL DEFAULT 1024)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `my_assistant` (" +
                    "`id` TEXT NOT NULL PRIMARY KEY, " +
                    "`name` TEXT, " +
                    "`prompt` TEXT, " +
                    "`avatar` TEXT, " +
                    "`avatarImageBase64` TEXT, " +
                    "`firstDialogue` TEXT, " +
                    "`type` TEXT, " +
                    "`allowAutoLife` INTEGER NOT NULL DEFAULT 0, " +
                    "`allowProactiveMessage` INTEGER NOT NULL DEFAULT 0, " +
                    "`options_json` TEXT, " +
                    "`updatedAt` INTEGER NOT NULL DEFAULT 0)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_assistant_binding` (" +
                    "`sessionId` TEXT NOT NULL PRIMARY KEY, " +
                    "`assistantId` TEXT)"
                )

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_session_assistant_binding_assistantId` " +
                    "ON `session_assistant_binding` (`assistantId`)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `message` ADD COLUMN `reasoning` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `thinkingElapsedMs` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `message` ADD COLUMN `embedding` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `message` ADD COLUMN `turnId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `assistantId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `synced` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `syncAttempts` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `lastAttemptAt` INTEGER")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `lastError` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_message_pending_sync` " +
                    "ON `message`(`synced`, `createdAt`)"
                )
            }
        }

        /** v7：session_chat_options 加 sessionAvatarImageBase64 列，让会话级别可覆盖助手头像图片。 */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `session_chat_options` ADD COLUMN `sessionAvatarImageBase64` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v8：session_chat_options 加 maxTokens / frequencyPenalty / presencePenalty / topK，
         * 都是 NULL 表示「未设置 → 走 ChatParamsResolver 回退到角色 / 模型默认 / 代码默认」。
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `session_chat_options` ADD COLUMN `maxTokens` INTEGER")
                db.execSQL("ALTER TABLE `session_chat_options` ADD COLUMN `frequencyPenalty` REAL")
                db.execSQL("ALTER TABLE `session_chat_options` ADD COLUMN `presencePenalty` REAL")
                db.execSQL("ALTER TABLE `session_chat_options` ADD COLUMN `topK` INTEGER")
            }
        }

        /**
         * v9 (Memory Graph Phase A1):
         *  - message 表加 toolCallsJson / toolCallId / toolName, 让 OpenAI 风格的
         *    assistant(tool_calls) 与 role=tool 结果可以入库; 现有行默认空字符串。
         *  - 新增 relationship_state 表, 作为 wi-chat-server 关系状态的客户端冷缓存。
         *
         * 配套常量见 Message.ROLE_SYSTEM=2 / ROLE_TOOL_CALL=3 / ROLE_TOOL_RESULT=4。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `message` ADD COLUMN `toolCallsJson` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `toolCallId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `toolName` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `relationship_state` (" +
                    "`assistantId` TEXT NOT NULL PRIMARY KEY, " +
                    "`closeness` INTEGER NOT NULL DEFAULT 0, " +
                    "`trustLevel` TEXT NOT NULL DEFAULT '', " +
                    "`sharedTopicsJson` TEXT NOT NULL DEFAULT '[]', " +
                    "`lastEmotionalTone` TEXT NOT NULL DEFAULT '', " +
                    "`lastInteractionAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`fetchedAt` INTEGER NOT NULL DEFAULT 0, " +
                    "`rawJson` TEXT NOT NULL DEFAULT '')"
                )
            }
        }

        /**
         * v10 (Proactive Chat / 自动对话 Phase 1):
         *  - session_chat_options 加 autoChatEnabled / proactiveCountToday / proactiveResetDate,
         *    支撑 per-session 自动对话开关 + 每日预算计数.
         *  - message 表加 proactiveKind, 标记 split / follow-up 来源, 用于审计与统计.
         *
         * 协议见 chat/ProactiveMetaParser, chat/ProactivePromptBuilder.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `session_chat_options` ADD COLUMN `autoChatEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `session_chat_options` ADD COLUMN `proactiveCountToday` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `session_chat_options` ADD COLUMN `proactiveResetDate` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `message` ADD COLUMN `proactiveKind` INTEGER NOT NULL DEFAULT 0")
            }
        }

        @JvmStatic
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ai_chat_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .allowMainThreadQueries() // 临时：待优化2(ViewModel)完成后移除
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
