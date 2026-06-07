package com.example.aichat.story

import android.app.Activity
import android.widget.Toast
import com.example.aichat.SessionOutlineStore
import java.util.concurrent.Executor

/**
 * 「生成书籍」入口 — S6 接 Story Tools 调用主对话模型,反向初始化
 * world / roles / volume / rules。当前为 S2 完成时的占位实现:
 * 校验必要前置条件,提示「即将上线」。S6 接入完整实现。
 */
object BookGenerator {

    fun run(activity: Activity, sessionId: String, executor: Executor, runOnUiThread: (Runnable) -> Unit) {
        val items = SessionOutlineStore(activity).getAll(sessionId)
        val chapterCount = items.count { it.type == StoryTypes.CHAPTER }
        if (chapterCount == 0) {
            Toast.makeText(activity, "请先添加至少一章章节大纲", Toast.LENGTH_SHORT).show()
            return
        }
        // S6 接 Story Tools 完整实现; 当前仅占位提示
        Toast.makeText(
            activity,
            "生成书籍 (S6 接入): 输入 $chapterCount 章, 将调用模型 + Story Tools 反向初始化",
            Toast.LENGTH_LONG
        ).show()
    }
}
