package com.example.aichat.chat

/**
 * Parsed result of one model回复尾部的 META 块.
 *
 * 协议: 模型在正文之后追加一段:
 * ```
 * <<<META
 * {"split": [...] | null, "followUp": {...} | null}
 * META>>>
 * ```
 *
 * 客户端在 onSuccess 阶段解析:
 *   - cleanContent: 移除 META 块后的可见正文 (写入 DB / 显示给用户)
 *   - meta:         解析出的 split / followUp 决策, null 表示模型未发或解析失败
 */
data class ProactiveMetaExtractResult(
    @JvmField val cleanContent: String,
    @JvmField val meta: ProactiveMeta?,
)

/**
 * 解析自模型 META 块.
 *
 * - [split]: 模型给定的拆分版本 (替代正文用于"打字模拟"). null = 不拆分, 直接显示 cleanContent.
 *           每段都会作为独立的 Message 入库, proactiveKind=1.
 * - [followUp]: 沉默期追问决策. null = 不追问.
 */
data class ProactiveMeta(
    @JvmField val split: List<String>?,
    @JvmField val followUp: ProactiveFollowUp?,
)

/**
 * 沉默期 follow-up 决策.
 *  - [afterSec]: 用户上次输入到 follow-up 之间的等待秒数 (clamp 30..1800)
 *  - [intent]:   模型自己描述的 follow-up 意图, 喂回下一次 prompt 当 hint
 */
data class ProactiveFollowUp(
    @JvmField val afterSec: Int,
    @JvmField val intent: String,
)
