# Character 模式 — 角色 / 人物对话

> Character 模式优化沉浸式角色对话体验：括号情绪渲染、TTS 朗读、proactive 自动推送、不显示"操作工具栏"打断阅读。

---

## 1. 触发条件

`MyAssistant.type == "character"` → `SessionMode.CHARACTER` → [`CharacterModeStrategy`](../../app/src/main/java/com/example/aichat/session/CharacterModeStrategy.kt)。

---

## 2. UI 行为差异

| 元素 | character 行为 | 默认行为 |
|---|---|---|
| 助手消息底部 pinned action 工具栏 | **隐藏**（不显示 regenerate/edit/copy 等） | 显示在最新一条 |
| Reasoning 折叠胶囊 | **禁用**（强制展示完整 reasoning） | 默认折叠 |
| 自动 TTS 按钮（toolbar） | **显示** | 隐藏 |
| 助手消息内 `[narration]` 段 | 渲染成 `ios_section_label` 灰色 | 普通 markdown |
| 自动滚到最新消息 | **不自动聚焦**（允许用户停留在中段） | 自动聚焦 |
| 助手左侧角色头像 | 显示（用助手 avatar） | 不显示 |

---

## 3. 括号情绪协议（`[emotion]`）

Character 模式下助手回复约定使用 emoji 标记情绪 + 圆括号标注 narration（旁白/动作）：

```
🤫小声地说道（凑近你的耳边）你听我说……
😘（笑着拉住你的手）走吧。
```

解析在 [`EmotionTagParser`](../../app/src/main/java/com/example/aichat/EmotionTagParser.kt)：
- 句首 emoji（🤫😘🥺😳🥰😊😢😌🥵 等）→ 取对应 [`SpeechProfile`](../../app/src/main/java/com/example/aichat/BracketEmotionMapper.kt)，UI 隐藏 emoji
- 圆括号段落 → `narrationRanges`，UI 染灰色
- 提取 `ttsText`（去掉 emoji 后的纯文本，括号段保留）+ `profile`（emotion / emotionScale / speechRate / loudnessRate / pitchRate）

---

## 4. 助手气泡渲染（character）

[`CharacterDisplayRenderer.render(anchor, content)`](../../app/src/main/java/com/example/aichat/adapter/CharacterDisplayRenderer.kt)：

```kotlin
1. EmotionTagParser.parse(content) → 取 displayText（去 emoji 后的）和 narrationRanges
2. 没有 narration 段 → 直接返回 displayText
3. 有 narration → SpannableString + ForegroundColorSpan(ios_section_label) 染色
```

调用：MessageAdapter.bindAssistantContent / bindAssistantContentStreaming 在 `characterMode == true` 时调 CharacterDisplayRenderer.render 替代普通 markdown。

---

## 5. 自动 TTS 朗读

`autoTtsEnabled` 是 character 专属，per-assistant 持久化在 [`AutoReadStore`](../../app/src/main/java/com/example/aichat/AutoReadStore.kt)（SharedPreferences）。

```
LLM onSuccess → Activity.maybeAutoReadAssistantMessage(message, content)
  if !autoTtsEnabled || !mode.supportsAutoTts → return
  handleVoicePlay(message)
    payload = mode.resolveVoicePlay(message, raw)  ← character 用 EmotionTagParser
    text = payload?.text ?: raw
    speechParams = payload?.speechParams
    VolcEngineTTSManager.speak(text, speechParams)  ← 火山 TTS API
```

[`CharacterModeStrategy.resolveVoicePlay`](../../app/src/main/java/com/example/aichat/session/CharacterModeStrategy.kt)：
```kotlin
override fun resolveVoicePlay(message: Message, raw: String): VoicePlayPayload {
    val parsed = EmotionTagParser.parse(raw)
    val profile = parsed.profile
    val speechParams = if (profile != null && profile.hasAnyParam())
        VolcEngineHttpTTS.SpeechParams(...) else null
    return VoicePlayPayload(parsed.ttsText, speechParams)
}
```

非 character 模式 strategy 返回 `null`，调用方走「原文 + 默认参数」。

播放控制：[VolcEngineTTSManager](../../app/src/main/java/com/example/aichat/VolcEngineTTSManager.kt) 单例，同一时刻只有一条 message 播放。点正在播的消息 → stop。

---

## 6. Proactive 自动对话（角色独有）

详细见 [proactive-message-protocol.md](proactive-message-protocol.md) + [split-message-design-review.md](split-message-design-review.md)。压缩版：

```
Character 助手开启 allowProactiveMessage + 配过 autoChat 设置 → 启用
  ↓
Server 端（wi-chat-server）按规则触发 → ws push to client
  ↓
WsClient (sync/) 收到 proactive payload
  ↓ 当前 session 是该角色且 ActiveSessionTracker 标记为 active
ChatViewModel.ensurePlanner().run(...)（[ProactiveChatPlanner](../../app/src/main/java/com/example/aichat/chat/ProactiveChatPlanner.kt)）
  ↓ 规划 split: 一段长回复拆成多条短消息按节奏发
  ↓ 或 follow-up: 后台 WorkManager 延时再发一条
ChatViewModel.proactiveMessageEvent (LiveData)
  ↓ KIND_REPLACE / KIND_APPEND / KIND_REMOVE
Activity.handleProactiveMessageEvent
  ↓ in-place 更新 allMessages list + applyMessagesAndTitle()
```

`message.proactiveKind` 字段标记：
- `0` 普通消息
- `1` proactive split 段（同一 turnId 多条）
- `2` proactive follow-up（独立 turn）

**删除规则不同**：split 组要按 turnId 整组删（[onDelete](../../app/src/main/java/com/example/aichat/ChatSessionActivity.kt) 里的 `splitGroupTurnId` 逻辑）。

---

## 7. 关系状态（relationshipState）

Character 助手有 per-session 关系状态（亲密度 / 信任 / 共同话题 / 情绪），存 [`RelationshipStateStore`](../../app/src/main/java/com/example/aichat/RelationshipStateStore.kt)：

```
ChatViewModel.buildSystemPromptIfRoleplay(assistantId):
  if not character → return ""
  else:
    prompt += RelationshipStateStore.buildPromptHintForAssistant(assistantId)
    例如: "（提示：你和用户认识 30 天，亲密度 7/10，最近聊过 X, Y）"
```

Hint 拼到 system prompt 末尾，让模型把关系状态融入回复语气。

---

## 8. 开场白（firstDialogue）

Character 助手在 [MyAssistant](../../app/src/main/java/com/example/aichat/MyAssistant.kt) 有 `firstDialogue` 字段。新会话第一次打开时：

```
Activity.maybeInsertAssistantOpeningMessage:
  if allMessages 非空 → return
  取 assistant.firstDialogue
  如果非空 → 写入一条 ROLE_ASSISTANT 消息
```

不区分 character / writer / default，只要 assistant 配了 firstDialogue 就会插。但实际使用主要在 character 场景。

---

## 9. 常见陷阱

1. **括号情绪解析 false-positive**：模型偶尔输出"(注：…)"这种非情绪括号，会被染灰。靠 prompt 引导减少。
2. **TTS 不播放**：检查
   - autoTtsEnabled 是否 on（toolbar 喇叭按钮）
   - AutoReadStore 配置正确（per-assistant id）
   - VolcEngineHttpTTS 的 API key 在设置里配过
   - message.content 是否非空
3. **Proactive 触发不稳**：server 端调度策略 + WorkManager 延时 + ActiveSessionTracker 状态混合，看 [proactive-message-protocol.md](proactive-message-protocol.md) 完整诊断。
4. **角色头像不显示**：检查 `assistant.avatar` 字段（URL 或 base64），以及 [AssistantAvatarHelper](../../app/src/main/java/com/example/aichat/AssistantAvatarHelper.kt) 加载是否报错。
5. **character 强制展开 reasoning** 导致超长 message 视觉拥挤 —— 这是设计取舍（角色对话不允许折叠避免破坏沉浸感）。要改：`CharacterModeStrategy.disablesAssistantCollapseToggle = false`，但要先讨论。
6. **Pinned action 工具栏不显示** —— character 设计意图。如果要让用户能 regenerate/edit，改 `CharacterModeStrategy.hidesPinnedActions = false`（会牺牲沉浸感）。

---

## 10. 加新 character 行为去哪改？

| 我想… | 改 |
|---|---|
| 加新 emoji 情绪映射 | `BracketEmotionMapper.kt` 的 SpeechProfile map |
| 改括号染色 | `CharacterDisplayRenderer.render`（adapter/） |
| 加新的 TTS 参数（如 pitch） | `VolcEngineHttpTTS.SpeechParams` data class + `CharacterModeStrategy.resolveVoicePlay` |
| 改自动 TTS 触发条件 | `Activity.maybeAutoReadAssistantMessage` + `CharacterModeStrategy.supportsAutoTts` |
| 加新的关系状态字段 | `RelationshipStateEntity` + `RelationshipStateStore.buildPromptHintForAssistant` |
| 改 proactive 调度 | server 端 + `sync/WsClient` + `proactive/` 包 + `ProactiveChatPlanner` |
| 加 character-only 的 prompt 注入 | `CharacterModeStrategy.buildUserMessageForApi`（目前空实现）|
