package com.example.aichat

data class SessionMeta(
    @JvmField var title: String = "",
    @JvmField var outline: String = "",
    @JvmField var avatar: String = "",
    @JvmField var category: String = "默认",
    @JvmField var favorite: Boolean = false,
    @JvmField var pinned: Boolean = false,
    @JvmField var hidden: Boolean = false,
    @JvmField var deleted: Boolean = false
)
