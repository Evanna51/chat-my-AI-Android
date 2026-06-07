package com.example.aichat.proactive

/**
 * Process-wide tracker for the session currently displayed on screen.
 *
 * ChatSessionActivity sets/clears this on onResume/onPause.
 * ProactiveFollowUpWorker checks it to decide between in-app refresh vs notification.
 */
object ActiveSessionTracker {

    @Volatile
    private var activeSessionId: String = ""

    @Volatile
    private var refreshCallback: (() -> Unit)? = null

    fun setActive(sessionId: String, onNewMessage: () -> Unit) {
        activeSessionId = sessionId
        refreshCallback = onNewMessage
    }

    fun clearActive() {
        activeSessionId = ""
        refreshCallback = null
    }

    fun isActive(sessionId: String): Boolean =
        sessionId.isNotEmpty() && activeSessionId == sessionId

    /** Called from Worker thread when a follow-up message is persisted in an active session. */
    fun notifyNewFollowUp() {
        refreshCallback?.invoke()
    }
}
