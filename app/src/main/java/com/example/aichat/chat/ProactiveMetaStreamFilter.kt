package com.example.aichat.chat

/**
 * Stateful streaming filter that prevents the `<<<META ... META>>>` tail block
 * from leaking into [com.example.aichat.ChatService.ChatCallback.onPartial] events
 * during 自动对话 streaming.
 *
 * Why we need this: the model emits META at the very end of its reply, but
 * tokens arrive chunk-by-chunk. Without filtering the tail block flickers in
 * the user's bubble for ~1 token-window before [ProactiveMetaParser.extract]
 * runs at onSuccess and replaces the bubble with cleanContent.
 *
 * Algorithm (inspired by [InlineThinkProcessor]):
 *  - Buffer the last `OPEN_TAG.length - 1 = 6` chars across chunks so a marker
 *    that splits across chunk boundaries (`<<<` then `META`) is still detected.
 *  - When `<<<META` first appears in the combined buffer, emit text up to the
 *    marker and switch to swallow mode — drop every subsequent chunk until end.
 *  - The full raw content is still accumulated by ChatService into `fullContent`,
 *    so onSuccess can run [ProactiveMetaParser.extract] against the full text.
 *
 * Lifecycle: create one filter per stream round; call [process] for each
 * post-think content delta; call [flushTail] once when the stream finishes.
 */
class ProactiveMetaStreamFilter {

    companion object {
        private const val OPEN_TAG = "<<<META"
        // Hold up to OPEN_TAG.length - 1 chars across chunks (max prefix match)
        private const val MAX_HOLD = 6
    }

    /** Once we see `<<<META`, we drop everything else from the visible stream. */
    private var inMeta: Boolean = false

    /** Tail bytes held over from previous chunk in case marker straddles. */
    private var heldTail: String = ""

    /**
     * Process one content delta.
     * Returns the substring that is safe to emit via onPartial (may be empty).
     * Subsequent text arriving after `<<<META` returns "" (swallowed).
     *
     * Note: this only filters CONTENT chunks. Reasoning, usage, tool_calls are
     * handled by other paths in ChatService and do not need filtering.
     */
    fun process(chunk: String?): String {
        if (inMeta) return ""
        val s = chunk ?: ""
        if (s.isEmpty() && heldTail.isEmpty()) return ""
        val combined = heldTail + s
        val idx = combined.indexOf(OPEN_TAG)
        if (idx >= 0) {
            // Marker found. Emit prefix only; swallow rest.
            inMeta = true
            heldTail = ""
            return combined.substring(0, idx)
        }
        // No marker yet. Hold the last MAX_HOLD chars (max possible incomplete prefix).
        if (combined.length <= MAX_HOLD) {
            heldTail = combined
            return ""
        }
        val cut = combined.length - MAX_HOLD
        heldTail = combined.substring(cut)
        return combined.substring(0, cut)
    }

    /**
     * Drain any held bytes at end of stream. If we never saw a META marker,
     * the held bytes are real content and should be emitted; if we did, they
     * were already discarded.
     */
    fun flushTail(): String {
        if (inMeta) {
            heldTail = ""
            return ""
        }
        val out = heldTail
        heldTail = ""
        return out
    }
}
