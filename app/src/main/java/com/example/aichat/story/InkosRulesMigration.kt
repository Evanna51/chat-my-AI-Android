package com.example.aichat.story

import android.content.Context
import android.util.Log
import com.example.aichat.AppDatabase
import com.example.aichat.SessionOutlineStore

/**
 * v16 启动后置迁移:
 *
 * Room v15→v16 把每个 session 的非空 `inkosBookRulesYaml` 拷到了
 * `_pending_inkos_rules_migration` 临时表。这里负责:
 *   1) 读出来; 2) 解析成 [StoryMeta.RulesMeta]; 3) 写一条 `type=rules` 的 outline item
 *   到 SessionOutlineStore (SharedPreferences); 4) 删 row; 5) 表空了就 DROP。
 *
 * 不影响 startup 时长 — 一般几行而已。
 */
object InkosRulesMigration {

    private const val TAG = "InkosRulesMigration"

    fun runIfPending(context: Context) {
        val db = try {
            AppDatabase.getInstance(context).openHelper.writableDatabase
        } catch (t: Throwable) {
            Log.w(TAG, "open db failed, skip", t)
            return
        }

        // 表不存在 (从未走过 v16 迁移或已清理完毕) → 退出
        val exists = try {
            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='_pending_inkos_rules_migration'"
            ).use { it.moveToFirst() }
        } catch (t: Throwable) {
            Log.w(TAG, "probe table failed", t); false
        }
        if (!exists) return

        val store = SessionOutlineStore(context)
        val processed = mutableListOf<String>()

        try {
            db.query("SELECT sessionId, yaml FROM `_pending_inkos_rules_migration`").use { c ->
                while (c.moveToNext()) {
                    val sid = c.getString(0).orEmpty()
                    val yaml = c.getString(1).orEmpty()
                    if (sid.isEmpty()) continue

                    val existing = store.getAll(sid).any { it.type == StoryTypes.RULES }
                    if (existing) {
                        processed += sid
                        continue
                    }

                    val meta = StoryMeta.parseLegacyYaml(yaml)
                    val metaJson = StoryMeta.toJson(meta)
                    store.add(sid, StoryTypes.RULES, "叙事规则", "", metaJson)
                    processed += sid
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "iterate failed", t)
        }

        // 删已处理的 row + 收尾
        for (sid in processed) {
            try {
                db.execSQL(
                    "DELETE FROM `_pending_inkos_rules_migration` WHERE sessionId = ?",
                    arrayOf<Any>(sid),
                )
            } catch (t: Throwable) {
                Log.w(TAG, "delete row $sid failed", t)
            }
        }

        val remaining = try {
            db.query("SELECT COUNT(*) FROM `_pending_inkos_rules_migration`").use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (_: Throwable) { -1 }
        if (remaining == 0) {
            try { db.execSQL("DROP TABLE `_pending_inkos_rules_migration`") } catch (_: Throwable) {}
        }
        Log.i(TAG, "migrated ${processed.size} session(s), remaining=$remaining")
    }
}
