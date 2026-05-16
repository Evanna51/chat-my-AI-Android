package com.example.aichat

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application-scoped, never-shutdown executor for "must complete" DB writes
 * triggered by long-running stream callbacks.
 *
 * Why this exists:
 *   ChatViewModel.executor is bound to ViewModel lifecycle — when the user
 *   leaves the chat page (Activity finish), `onCleared` shuts it down. But the
 *   OkHttp stream is intentionally NOT cancelled (we want the response to
 *   finish in background and persist). When the stream's `onSuccess` finally
 *   fires, the callback's `executor.execute { db.insert(...) }` lands on a
 *   shut-down executor → RejectedExecutionException → the message never lands
 *   in the DB. Reopening the chat shows a blank where the assistant reply
 *   should be.
 *
 *   Same problem for [com.example.aichat.chat.ProactiveChatPlanner.applySplit]
 *   — split parts are scheduled with delays and persist via the same dead
 *   executor.
 *
 * Solution: route those writes through this app-scoped pool. It outlives
 * Activity / ViewModel lifecycles and only dies when the process dies. Daemon
 * thread so it never blocks process exit.
 *
 * Threading: single thread is fine — DB inserts are serial anyway, and the
 * volume is tiny (one message + ~1-5 split parts per turn).
 */
object IngestExecutor {

    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chat-ingest").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    fun execute(task: Runnable) {
        exec.execute(task)
    }
}
