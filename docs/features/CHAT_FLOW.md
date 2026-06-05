# 聊天主流程 — 一条用户消息从输入到上屏

> 这是项目最核心的一条数据流。所有 ChatService / Activity 改动都要先理解这条链路。

---

## 0. TL;DR

```
[输入框文本]
    ↓ "发送" 按钮
ChatSessionActivity.sendMessageFromText(text)
    ├─ attachmentController.composeMessageWith(text)    ← 拼接附件
    ├─ mode.buildUserMessageForApi(text, ctx)           ← writer 注入大纲块
    └─ dispatchChatRequest(history, apiUserMessage)
        ↓
ChatViewModel.doChatRequest(...)
    ↓ 持有 chatGenerator: ChatGenerator = ChatService(...)
ChatService.chat(history, userMsg, options, callback, toolBridge): ChatHandle
    ├─ buildMessages(...)                ← 装配 ChatApi.ChatRequest.messages
    ├─ applyModelDefaultsToRequest(...)  ← 模型默认参数
    └─ streamChat(request, options, ...) ← SSE 长连接
        ↓ 每收到一段 delta
        ↓ 回调到 ChatCallback
ChatCallback.onPartial(delta) / onReasoning / onToolCallStart / onSuccess
    ↓ ChatViewModel 转成 StreamDeltaEvent 入队 + LiveData postValue
Activity.handleStreamDeltaEvent
    ├─ partial → streamTypewriter.enqueueDelta(msg, delta) ← 打字机帧循环
    │             └─ adapter.renderStreamingMessageIfVisible(msg)
    ├─ reasoning → adapter.notifyMessageChanged(msg)
    ├─ toolCallStart → 显示"正在调用工具"占位
    └─ success → mode.onAssistantStreamDone() / persist DB / maybeAutoNamingThreadTitle
```

---

## 1. 关键文件 + 行号锚点

| 阶段 | 文件 | 入口符号 |
|---|---|---|
| UI 触发发送 | [ChatSessionActivity.kt](../../app/src/main/java/com/example/aichat/ChatSessionActivity.kt) | `sendMessageFromText` |
| 改写 user message | 同上 | `buildUserMessageForApi` → `mode.buildUserMessageForApi` |
| 上下文拉取（可选） | 同上 | `dispatchChatRequestWithRemoteContextIfEnabled` |
| 实际请求派发 | 同上 | `dispatchChatRequest` |
| ViewModel 入口 | [ChatViewModel.kt](../../app/src/main/java/com/example/aichat/ChatViewModel.kt) | `doChatRequest` |
| ChatService 入口 | [ChatService.kt](../../app/src/main/java/com/example/aichat/ChatService.kt) | `chat()` |
| 装配 request.messages | 同上 | `buildMessages` (internal) |
| SSE 解析 | 同上 | `streamChat` (internal) |
| 流式 delta 回到 UI | [ChatSessionActivity.kt](../../app/src/main/java/com/example/aichat/ChatSessionActivity.kt) | `handleStreamDeltaEvent` |
| 打字机消费 delta | [StreamTypewriter.kt](../../app/src/main/java/com/example/aichat/session/StreamTypewriter.kt) | `enqueueDelta` |
| Adapter 单条 partial 重绘 | [MessageAdapter.kt](../../app/src/main/java/com/example/aichat/MessageAdapter.kt) | `renderStreamingMessageIfVisible` |
| 流结束持久化 | [ChatViewModel.kt](../../app/src/main/java/com/example/aichat/ChatViewModel.kt) | `persistMessageAsync` / Activity 的 `persistSessionMessagesAsync` |

---

## 2. 三个状态层

| 层 | 状态字段 | 谁拥有 |
|---|---|---|
| **生成中** | `activeChatHandle: ChatHandle?`、`activeStreamingMessage: Message?`、`activeResponseToken: Long` | Activity + ViewModel 各一份 |
| **打字机** | `streamingTargetMessage`、`pendingChars`、`typewriterRunning` | StreamTypewriter（内部） |
| **持久化** | DB.message 表（每流结束写） | ChatViewModel.executor |

**重要不变量**：
- `activeStreamingMessage` 在 Activity 里改值时，setter 自动同步 `streamTypewriter.setTarget(value)`（[ChatSessionActivity.kt:267](../../app/src/main/java/com/example/aichat/ChatSessionActivity.kt) 那块）
- `activeResponseToken` 每次新请求自增；旧请求的回调用 token 比对，过期则忽略
- `streamingTargetMessage` 不等于 `activeStreamingMessage`：前者是 typewriter 内部 ref，后者是「LLM 正在生成的 assistant message」。生命周期通常一致但不应耦合（见 [STREAMING.md](STREAMING.md)）

---

## 3. 模式如何改写消息（writer）

WRITER 模式下，用户输入会被 `WriterModeStrategy.buildUserMessageForApi` 改写：

```kotlin
override fun buildUserMessageForApi(rawInput: String, ctx: SessionContext): String {
    val source = rawInput.trim()
    if (source.isEmpty() || ctx.writerOutlineBlock.isEmpty()) return source
    return "$source\n\n【写作大纲与资料】\n${ctx.writerOutlineBlock}\n\n请严格参考以上内容..."
}
```

Activity 构造 `SessionContext` 时填充 `writerOutlineBlock`（[ChatSessionActivity.kt 中 `buildSessionContext()`](../../app/src/main/java/com/example/aichat/ChatSessionActivity.kt) 找）。

CHARACTER / DEFAULT 模式不改写（接口默认 passthrough）。

---

## 4. 上传消息列表的额外处理（writer）

发请求前，历史助手消息要做节选避免超 context：

```kotlin
// WriterModeStrategy.buildHistoryForApi:
// - 最新一条助手消息：取前 1000 字 / 中段 1000 字 / 尾段 1000 字（buildLastAssistantExcerpt）
// - 更早的助手消息：取前 500 字
// - user 消息和 character 模式：原样不动
```

CHARACTER / DEFAULT 模式不节选。

---

## 5. 自动起标题（首发消息后）

会话第一条用户消息发出后，触发自动命名：

```
Activity.sendMessageFromText → 拼接历史 → 派发请求
  ↓ 同时（流式开始后）
Activity.maybeAutoGenerateThreadTitle(firstUserMessage)
  → ViewModel.generateThreadTitle  (内部走 chatGenerator.generateThreadTitle)
  → ChatService.generateThreadTitle  ← 1 行 forwarder
  → ChatTitleGenerator.generate
      ↳ 走「话题命名」专用模型配置 (AiModelConfig.getConfigForThreadNaming)
      ↳ 同步 retrofit (不流式), 30s 超时
      ↳ 返回 3-12 字标题
```

回调持久化到 `SessionMetaStore`。详细见 [chat/ChatTitleGenerator.kt](../../app/src/main/java/com/example/aichat/chat/ChatTitleGenerator.kt)。

---

## 6. 工具调用（tool_calls）

如果模型返回 `tool_calls` 而非 content：

```
ChatService.streamChat 累积 tool_call delta（[ChatToolCallAccumulator](../../app/src/main/java/com/example/aichat/chat/ChatToolCallAccumulator.kt)）
  → onToolCallStart(toolName) 回调（UI 显示"调用 X"占位）
  → ToolBridge.execute(toolName, args)（[sync/ToolBridge.kt](../../app/src/main/java/com/example/aichat/sync/ToolBridge.kt)）
      ↳ search_memory / correct_memory / web_search
  → 把 tool_call + tool_result 两条 message 通过 callback.onToolMessageRecorded 回到 ViewModel
  → ViewModel 写 DB（role=ROLE_TOOL_CALL / ROLE_TOOL_RESULT, assistantId=空 → 不上传 server）
  → 继续下一轮 streamChat（带工具结果），最多 TOOL_LOOP_MAX_ROUNDS = 3 轮
```

UI 渲染：ToolCall/ToolResult 行被 [ToolCallMessageBinder](../../app/src/main/java/com/example/aichat/adapter/ToolCallMessageBinder.kt) 格式化成一段简短摘要，塞进下一条 assistant 消息的 reasoning 折叠区。

---

## 7. 取消请求

用户点 stop 按钮：
```
Activity.stopLatestResponse
  → handle.cancel()           ← ChatService.ChatHandleImpl 设 isCancelled=true
  → activeChatHandle = null
  → activeResponseToken 递增（旧 callback 进来会被 token 检查丢弃）
  → handleResponseStopped(target)
      ├─ streamTypewriter.drainPendingTo(streamingMessage)  ← 把残余字符塞回消息体
      ├─ persist 或 删除空消息
      └─ streamTypewriter.flushNow() → applyMessagesAndTitle
```

---

## 8. 常见陷阱

1. **不要在 `chat()` 里加 `if (mode == WRITER)`** —— 改 user message 是 strategy 的事，service 应该是模式无关的。
2. **streamChat 当前是 internal**，writer service 通过 `service.streamChat(...)` 调用。R7 接 inkos 时新建 `InkosGenerator implements ChatGenerator`，不要扩 ChatService。
3. **toolCall round 数有上限**（`TOOL_LOOP_MAX_ROUNDS = 3`）—— 防死循环。改大要慎重（用户会等很久）。
4. **`activeResponseToken` 的 race**：旧请求的 callback 可能在新请求开始后才到，必须先 `token == activeResponseToken` 检查再处理。
5. **proactive 自动对话不走这条流程**：见 [proactive-message-protocol.md](proactive-message-protocol.md)。它由 server 推送 + WorkManager 触发，进入 `ChatViewModel.proactiveMessageEvent` 而非 `streamDeltaEvent`。
6. **首发消息可能附带远程上下文**（chat-server 的 context fetch）：`dispatchChatRequestWithRemoteContextIfEnabled` 会先打一个 GET 请求拿历史摘要，然后再发起 chat。这步可能拖延 1-3 秒，UI 期间显示"思考中…"占位。
7. **流式过程中切换 Activity（onPause → onResume）**：handle 不会取消，请求继续后台跑，结果由 ViewModel 接收并持久化；Activity 回来后 `applyMessagesAndTitle()` 拉新数据。

---

## 9. 加新功能时去哪改？

| 我想… | 改 |
|---|---|
| 给某模式注入额外系统 prompt | 对应 strategy 的 `buildUserMessageForApi` 或 `buildHistoryForApi` |
| 加一种工具 | `sync/ToolBridge` 注册 + `sync/MemoryToolApi` 加 HTTP；`ChatViewModel` 在 systemPrompt 描述里加用法 |
| 改流式 throttle 节奏 | `session/StreamTypewriter.kt` 内的常量 RENDER_THROTTLE_MS / FRAME_MS / CHARS_PER_FRAME |
| 给某模型加默认参数 | `ModelDefaultParams.kt`（数据） + `ChatService.applyModelDefaultsToRequest`（应用逻辑） |
| 加 provider 识别 | `ChatService.resolveProviderId` / `isLocalOpenAiCompatibleProvider` 等 boolean helper |
| 在流结束后做副作用（比如统计） | `mode.onAssistantStreamDone` 钩子（目前各 strategy 都默认空实现） |
