# 流式渲染 — 打字机 / Reasoning / 工具调用

> 流式是这个 App 最复杂的子系统之一。这份文档把"一段 SSE delta → 屏幕上一个字符"的链路展开。

---

## 1. 三个并行子系统

| 子系统 | 文件 | 节奏 |
|---|---|---|
| **打字机** | [session/StreamTypewriter.kt](../../app/src/main/java/com/example/aichat/session/StreamTypewriter.kt) | 16ms/帧，4 字符/帧 |
| **Render throttle** | 同上 | 24ms（quiet） / 48ms（pendingChars ≥ 80） |
| **自动滚到底** | Activity.maybeAutoScrollOnStreamTick | 300ms throttle |

三者协同：SSE 推 delta 进 typewriter 的 `pendingChars` 队列 → typewriter 每帧消费 4 字符并增量更新单条 RV item → 每次 tick 后告诉 Activity「我刚更新了」→ Activity 决定是否 auto-scroll。

如果 RV 当下没有该消息的 visible holder（用户滚走了 / 还没 bind），typewriter 退化到 throttled render（让 Activity 走完整 `applyMessagesAndTitle`）。

---

## 2. 打字机生命周期

```
LLM 第一段 delta 到达
  ↓ Activity.handleStreamDeltaEvent.PartialDelta
streamTypewriter.enqueueDelta(activeStreamingMessage, delta)
  ↓ 第一次调用：设置 streamingTargetMessage + 启动 typewriterRunnable
  ↓ 后续调用：append 到 pendingChars，typewriter 已在跑就不重启

typewriterRunnable 每 16ms：
  1. 从 pendingChars 取最多 4 字符
  2. 拼到 streamingTargetMessage.content 末尾
  3. adapter.renderStreamingMessageIfVisible(msg) ← 单条 RV item incremental 重绘
  4. 如果 holder 不可见 → schedule render throttle
  5. 否则 host.onTickRendered() → Activity.maybeAutoScrollOnStreamTick
  6. pendingChars 还有 → postDelayed self
  7. 空 → 停帧循环

LLM onSuccess
  ↓ ChatViewModel.handleSuccess
streamTypewriter.drainPendingTo(message) ← 一次性消化残余字符
streamTypewriter.flushNow()              ← 让 Activity 重绘完整列表
```

---

## 3. activeStreamingMessage vs streamingTargetMessage

容易混淆，分清：

| 字段 | 在哪 | 表示 |
|---|---|---|
| `activeStreamingMessage` | Activity | "**LLM 正在生成内容**到这条 assistant message"。setter 联动 adapter 隐藏 message 底部工具栏 + 联动 typewriter target |
| `streamingTargetMessage` | StreamTypewriter 内部 | "**typewriter 帧循环当前消化字符**塞到这条 message"。生命周期通常一致但语义不同 |

R9 之后 setter 自动同步：
```kotlin
private var activeStreamingMessage: Message? = null
    set(value) {
        field = value
        historyAdapter.setStreamingAssistantMessage(value)
        currentAdapter.setStreamingAssistantMessage(value)
        if (::streamTypewriter.isInitialized) streamTypewriter.setTarget(value)
    }
```

不要直接操作 `streamTypewriter.setTarget`，让 setter 联动是最稳的。

---

## 4. Reasoning（思考过程）

OpenAI o1-style + 部分本地模型会发 `reasoning` delta（独立于 content）。

```
ChatService.streamChat 在 SSE 解析时区分:
  - delta.content     → callback.onPartial(text)
  - delta.reasoning   → callback.onReasoning(text)

ChatViewModel.handleStreamEvent:
  - onPartial(delta) → enqueue StreamDeltaEvent.PartialDelta
  - onReasoning(delta) → enqueue StreamDeltaEvent.ReasoningDelta (累积到 msg.reasoning 字段)

Activity.handleStreamDeltaEvent:
  - PartialDelta → streamTypewriter.enqueueDelta(...)
  - ReasoningDelta → directly mutate msg.reasoning + adapter.notifyMessageChanged
```

**reasoning 不走打字机**——它是「折叠区」内容，整段一次性展开，没字符级动画必要。

MessageAdapter 怎么渲染 reasoning：`bindReasoning(holder, msg, position)`。逻辑：
- 有 reasoning → 显示折叠/展开胶囊
- 默认折叠（除非用户上次展开过）
- 工具调用消息会被合并到下一条 assistant 的 reasoning 区前缀（[ToolCallMessageBinder.formatBuffer](../../app/src/main/java/com/example/aichat/adapter/ToolCallMessageBinder.kt)）

**Character 模式禁用 reasoning 折叠开关**（`DefaultModeStrategy.disablesAssistantCollapseToggle = false` vs `CharacterModeStrategy.disablesAssistantCollapseToggle = true`）。

---

## 5. 工具调用（tool_calls）

```
SSE delta 里有 tool_calls 数组：
  ChatToolCallAccumulator 累积（流式 tool_call 字段会分片）
  ↓ 完整后
ChatService.streamChat:
  - callback.onToolCallStart(toolName) → UI 显示"调用 X"
  - 创建 ToolMessageRecord(role=ROLE_TOOL_CALL, toolCallsJson=...)
  - callback.onToolMessageRecorded(record) → ViewModel 入 DB
  - ToolBridge.execute(toolName, argsJson)
  - 拿到结果 → 创建 ToolMessageRecord(role=ROLE_TOOL_RESULT, content=result)
  - callback.onToolMessageRecorded(record)
  - 进入下一轮 streamChat（带工具结果）

UI 显示:
  - 当前 streaming 期间, ROLE_TOOL_CALL / ROLE_TOOL_RESULT 行夹在两条 assistant 之间
  - MessageAdapter 把连续的 tool 行 buffer 起来, 用 ToolCallMessageBinder.formatBuffer 拼成
    一段 reasoning-风格的摘要 (🔧 调用 X(args) → name 返回:\nresult...)
  - 摘要塞到下一条 assistant message 的 reasoning 折叠区前缀
```

最多 3 轮工具循环（`ChatService.TOOL_LOOP_MAX_ROUNDS`），防死循环。

---

## 6. 性能调优可调常量

[StreamTypewriter.kt](../../app/src/main/java/com/example/aichat/session/StreamTypewriter.kt) companion：

| 常量 | 默认 | 含义 | 调大影响 |
|---|---|---|---|
| `FRAME_MS` | 16L | 帧间隔 | 字符显示更慢 |
| `CHARS_PER_FRAME` | 4 | 每帧消化字符数 | 字符显示更快但每帧渲染压力大 |
| `RENDER_THROTTLE_MS` | 24L | 正常 throttle 间隔 | 渲染更新更密 / 更稀 |
| `RENDER_THROTTLE_BUSY_MS` | 48L | pending 多时 throttle | 同上 |
| `RENDER_BUSY_PENDING_CHARS` | 80 | 触发 busy throttle 的 pending 阈值 | 大→更晚切到 busy throttle |

[ChatSessionActivity.kt](../../app/src/main/java/com/example/aichat/ChatSessionActivity.kt) companion：

| 常量 | 默认 | 含义 |
|---|---|---|
| `STREAM_AUTO_SCROLL_THROTTLE_MS` | 300L | 自动滚到底节流 |

---

## 7. 常见陷阱

1. **手动 `streamingTargetMessage = X`** — 这变量已搬到 typewriter 内部。新代码应该走 `activeStreamingMessage = X`（setter 自动同步）或直接 `streamTypewriter.setTarget(X)`。
2. **typewriter onSuccess 前末尾字符丢失** — 必须先 `drainPendingTo(message)` 再 `flushNow()`，否则 pending 里残留字符会被 stop() 丢掉。
3. **renderStreamingMessageIfVisible 和 notifyMessageChanged 是不同方法**：
   - `renderStreamingMessageIfVisible` 用于 typewriter 帧循环（找已绑定的 visible holder，直接更新 view）
   - `notifyMessageChanged` 用于 render throttle / 思考计时（走 RV change payload，更通用但慢一档）
4. **`activeResponseToken` race**：旧请求的 SSE 包可能在新请求开始后才到达，要在 callback 第一行检查 `if (token != activeResponseToken) return`。
5. **Character 模式不显示 reasoning 折叠** — `disablesAssistantCollapseToggle = true`。但 reasoning 字段本身还在 message 上，只是 UI 不让折。
6. **工具调用消息（role=3/4）不上传 server**：`assistantId=""` + SyncQueueDrainer 跳过空 assistantId 的行。改这一点要同步改 server 端导入逻辑。
7. **`maybeAutoScrollOnStreamTick` 节流 300ms**：流量快时 ~3 帧才 scroll 一次，看起来"跟不上"是正常的，避免每帧滚 → 卡顿。

---

## 8. 加新流式行为时去哪改？

| 我想… | 改 |
|---|---|
| 加一种 delta 字段（如新的 thinking 协议） | `ChatService.streamChat` 解析 + `ChatCallback` 加方法 + `ChatViewModel` 转 event + Activity 路由 |
| 改打字机节奏 | `StreamTypewriter` companion 常量 |
| 加流结束后的副作用 | `SessionModeStrategy.onAssistantStreamDone` 钩子（目前所有 strategy 都 default 空实现） |
| 工具调用结果显示样式 | `adapter/ToolCallMessageBinder` |
| Reasoning 折叠默认状态 | `MessageAdapter.bindReasoning` |
| 把工具调用上传 server | sync/SyncQueueDrainer 的 assistantId 过滤逻辑 |
