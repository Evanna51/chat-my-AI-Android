package com.example.aichat

import androidx.room.Ignore

class SessionSummary {
    @JvmField var sessionId: String = ""
    @JvmField var title: String = ""
    @JvmField var lastAt: Long = 0L
    @JvmField var lastMessage: String = ""

    @Ignore @JvmField var avatar: String = ""
    /** Base64 编码的头像图片：由绑定助手 (assistantId) 的 avatarImageBase64 派生，优先于 avatar 文本。 */
    @Ignore @JvmField var avatarImageBase64: String = ""
    @Ignore @JvmField var category: String = ""
    @Ignore @JvmField var favorite: Boolean = false
    @Ignore @JvmField var pinned: Boolean = false
    @Ignore @JvmField var hidden: Boolean = false
    @Ignore @JvmField var deleted: Boolean = false
    @Ignore @JvmField var outline: String = ""

    constructor()

    @Ignore
    constructor(sessionId: String, title: String, lastAt: Long) {
        this.sessionId = sessionId
        this.title = title
        this.lastAt = lastAt
    }
}
