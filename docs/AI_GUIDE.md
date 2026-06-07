# AI 导览 — 第一份要读的文档

> **目标读者**：刚拿到这个项目的 LLM / AI 助手。读完这份能在不读全仓的前提下回答 80% 的常见问题、定位 90% 的修改入口。
>
> **维护原则**：这份文档落后 1–2 个 release 是可接受的（只是路标）；落后 10 个 release 就要更新。每当三大胖文件（ChatService / ChatSessionActivity / MessageAdapter）的"主入口方法行号"发生大范围漂移时，更新 §3 索引表。

---

## 1. 项目一句话

Android 原生 AI 聊天 App（Kotlin），支持多模型多厂商（OpenAI 兼容 + 本地 llama / Gemini）、多会话、**三种助手模式**（默认 / 角色 / 作家），有自动对话（proactive）、流式 + 工具调用、TTS 朗读、跨端 server 同步（chat-server）。

---

## 2. 必读架构概念（按这个顺序理解）

### 2.1 三种「会话模式」(SessionMode)

每个会话绑定一个 `MyAssistant`，`assistant.type` 决定该会话的模式：

| `type` | enum | 行为差异 |
|---|---|---|
| `""` | `SessionMode.DEFAULT` | 普通聊天 |
| `"character"` | `SessionMode.CHARACTER` | 括号情绪渲染 / 自动 TTS / 不显示 pinned actions / 不允许折叠 reasoning |
| `"writer"` | `SessionMode.WRITER` | Toolbar 显示大纲按钮 / 用户输入注入大纲块 / 长助手消息节选 / 章节跳转 |

**模式差异通过 [SessionModeStrategy](../app/src/main/java/com/example/aichat/session/SessionModeStrategy.kt) 注入，不再有 `if (writerAssistant)` 分支**。看 [features/SESSION_MODES.md](features/SESSION_MODES.md) 了解如何加新模式。Writer 模式的故事结构（roles/relation/foreshadow/status/...）见 [features/STORY_TYPES.md](features/STORY_TYPES.md)。

### 2.2 三层架构

```
UI (Activity / Adapter / View)
  ↓ holds
SessionModeStrategy (无状态 object)
  ↓ holds
ChatGenerator (interface) ← 模式无关聊天入口
  ↓ implemented by
ChatService (OpenAI 兼容实现; 唯一持有 streamChat)
  └─ writer-only services (大纲 / 章纲 / 卷 / 知情)
```

### 2.3 数据流（一条用户消息从输入到上屏）

详见 [features/CHAT_FLOW.md](features/CHAT_FLOW.md)。压缩版：

```
User input → ChatSessionActivity.sendMessageFromText()
    → buildUserMessageForApi (经 mode.buildUserMessageForApi 注入大纲等)
    → ChatViewModel.doChatRequest()
    → ChatGenerator.chat() = ChatService.chat()
    → 内部 buildMessages + streamChat (SSE 解析)
    → onPartial / onReasoning / onSuccess 回调
    → ChatViewModel.streamDeltaEvent (LiveData)
    → Activity.handleStreamDeltaEvent
    → StreamTypewriter.enqueueDelta (打字机帧循环)
    → MessageAdapter.renderStreamingMessageIfVisible
```

---

## 3. 三大胖文件索引（**改 X 去哪里？**）

> 行号会漂移，但函数名稳定。先按表找函数名，再 grep 当前行号。

### ChatService.kt （[源](../app/src/main/java/com/example/aichat/ChatService.kt)，~870 行）
| 关键词 | 函数名 |
|---|---|
| 流式底层 SSE 解析 | `streamChat` (internal) |
| 普通聊天入口 | `chat()` |
| 装配请求消息 | `buildMessages` (internal) |
| Provider 识别（OpenAI / llama / Gemini） | `resolveProviderId`, `isLocalOpenAiCompatibleProvider`, `isLlamaProviderId` |
| 模型默认参数 | `applyModelDefaultsToRequest` |
| reasoning 显示开关 | `shouldShowReasoning`, `isIntrinsicReasoningModel` |
| stop 序列解析 | `parseStopSequences` |
| 起会话标题 | 转发到 `chat/ChatTitleGenerator` |
| Writer 大纲/章纲 | 转发到 `writer/Writer*Service` |

### ChatSessionActivity.kt （[源](../app/src/main/java/com/example/aichat/ChatSessionActivity.kt)，~1940 行）
| 关键词 | 函数名 / 字段 |
|---|---|
| 入口 | `onCreate` |
| 模式选择 | `resolveSessionMode()`, `applyModeToAdapters()` |
| 发送消息 | `sendMessageFromText` |
| 接 stream event | `handleStreamDeltaEvent` |
| 章节跳转 | `chapterJumpController.show()`（[ChapterJumpController](../app/src/main/java/com/example/aichat/session/ChapterJumpController.kt)） |
| 附件 chip 栏 | `attachmentController.xxx`（[ChatAttachmentController](../app/src/main/java/com/example/aichat/session/ChatAttachmentController.kt)） |
| 打字机 | `streamTypewriter.xxx`（[StreamTypewriter](../app/src/main/java/com/example/aichat/session/StreamTypewriter.kt)） |
| 自动滚到底 | `maybeAutoScrollToBottom`, `maybeAutoScrollOnStreamTick` |
| Markdown 转 HTML（导出用） | `markdownToHtml` |
| 编辑 / 删除 / 复制 message action | `bindMessageActions` |

### MessageAdapter.kt （[源](../app/src/main/java/com/example/aichat/MessageAdapter.kt)，~940 行）
| 关键词 | 函数名 |
|---|---|
| RV 派发 | `getItemViewType`, `onCreateViewHolder`, `bindViewHolder` |
| 助手气泡 reasoning | `bindReasoning` |
| 助手气泡正文 | `bindAssistantContent`, `bindAssistantContentStreaming` |
| 角色括号情绪渲染 | `adapter/CharacterDisplayRenderer.render` |
| 工具调用消息渲染 | `adapter/ToolCallMessageBinder.formatBuffer` |
| 折叠胶囊跟随 viewport | `adapter/CollapseAffixController.applyAffix` |
| 长消息折叠展开 | `toggleAssistantExpanded`, `maybeHideShortMessageCollapseToggle` |

---

## 4. 包/目录速查（包路径都在 `com.example.aichat`）

| 包 | 装什么 | 何时进 |
|---|---|---|
| 根包 `.` | 历史遗留 Activity / Adapter / DAO / Entity / 顶层数据类 | 改 UI / 改数据模型 |
| `chat/` | ChatGenerator 接口、ChatService 子组件（title gen / text helpers / json helpers）、proactive 自动对话工具 | 改聊天 API 协议 / proactive 行为 |
| `writer/` | Writer 模式专属：4 个 service + WriterJsonHelpers | 改大纲/章纲/卷/知情逻辑 |
| `session/` | SessionMode + 4 个 Strategy + 3 个 Controller（Attachment / ChapterJump / Typewriter） | 加新会话模式 / 改 Activity 中拆出来的子领域 |
| `adapter/` | MessageAdapter 子组件（character display / collapse affix / tool call binder） | 改单条消息渲染 |
| `sync/` | chat-server 同步、memory tool、ws、context fetch | 改跨端同步 / tool 协议 |
| `proactive/` | ActiveSessionTracker, WorkManager | 改 proactive 调度 |
| `widget/` | 自定义 View（TypingDotsView, MessageActionPopup 等） | 加自定义 View |

---

## 5. 改某个 feature 前必读的子文档

| 你要改… | 先读 |
|---|---|
| 用户输入 → 模型输出的链路 | [features/CHAT_FLOW.md](features/CHAT_FLOW.md) |
| 流式渲染 / 打字机 / reasoning / 工具调用气泡 | [features/STREAMING.md](features/STREAMING.md) |
| 加一种新的 assistant.type | [features/SESSION_MODES.md](features/SESSION_MODES.md) |
| 作家模式（大纲/章纲/卷/知情） | [features/WRITER_MODE.md](features/WRITER_MODE.md) |
| 角色模式（情绪/TTS/proactive） | [features/CHARACTER_MODE.md](features/CHARACTER_MODE.md) |
| Proactive 自动对话协议 | [features/proactive-message-protocol.md](features/proactive-message-protocol.md) + [features/split-message-design-review.md](features/split-message-design-review.md) |
| 同步到 chat-server / memory tool | [features/SYNC.md](features/SYNC.md) |
| WS 电池/网络优化 | [features/ws-battery-optimization.md](features/ws-battery-optimization.md) |
| 包结构 / 谁依赖谁 | [architecture/MODULE_MAP.md](architecture/MODULE_MAP.md) |
| UI 风格 / 主题 | [ui/UI_REDESIGN_PLAN.md](ui/UI_REDESIGN_PLAN.md) |

---

## 6. 反模式 / 容易踩的坑

1. **不要在 Activity 里加 `if (writerAssistant)` 这种分支**。R6 已经把所有模式分支收口到 `SessionModeStrategy`。新模式差异加在对应 strategy 类里。
2. **`streamChat` 是私有的，但已经 `internal`**。writer service 通过 `service.streamChat(...)` 访问；不要让任何外部包绕过这一层重新实现 SSE 解析。
3. **不要把按钮 callback 卡片用 `mcp__lark-myself-mcp` 发**——按钮不会触发任何 app 的 `card.action.trigger`。详情见全局 memory `feedback-proactive-lark-notifications`。
4. **修改 `MyAssistant.type` 字符串值**会让所有持久化数据失效；R1 已经用 `SessionMode.WRITER.raw` 这种间接引用，不要改 raw 值。
5. **三种 `JsonObject` 工具不要混用**：
   - `chat/ChatJsonHelpers` = 底层防御性 getter（getInt/getString/...）
   - `chat/ChatTextHelpers` = 文本/JSON 提取（cleanTitle, parseFirstJsonObject, stripThinkTags）
   - `writer/WriterJsonHelpers` = writer 专属 chapter plan 容错（normalizeChapterPlanJson 等）
6. **打字机 `streamingTargetMessage` 不是 `activeStreamingMessage`**：前者是 typewriter 内部 ref（每帧消化字符的目标），后者是 LLM 在生成的 assistant message。生命周期通常同步但语义不同。
7. **`Message` 字段 `proactiveKind`** 0=普通 / 1=proactive split / 2=proactive follow-up — 删除时要走专用入口避免 DB/UI 不一致（详见 split-message-design-review.md）。
8. **`.claude/worktrees/*`** 绝不能 commit（曾发生 gitlink 误提交事故）。用 `git add <file>` 单个加，不要 `git add -A`。
9. **不要给 master/main 直接 push**。本仓库走 dev → MR。
10. **直接键入 Unicode smart quote 字符（`"` `"`）作为 Kotlin char literal 会编译失败** —— 在 sanitizeJsonLikeText 之类需要时用 `“` 转义。

---

## 7. 重构里程碑（git history）

| commit | 内容 |
|---|---|
| `910ced3` R10 | ToolCallMessageBinder |
| `5d00888` R9 | StreamTypewriter |
| `2999a7f` R8 | AttachmentController + ChapterJumpController |
| `215a729` R6 | SessionModeStrategy（消灭所有 writer/character if 分支） |
| `0100566` R5 | ChatGenerator 接口 + 顶级 ChatCallback/ChatHandle |
| `cc6a543` R4 | writer 服务集（chapter plan / outline / volume / title） |
| `9d5bc45` R3 | ChatTextHelpers + WriterJsonHelpers |
| `f8a862c` R2 | CharacterDisplayRenderer + CollapseAffixController |
| `e649a66` R1 | SessionMode enum |

历史完整 plan 见 [archive/refactor/](archive/refactor/)。

---

## 8. 当下行数（基线，会漂）

| 文件 | 起始 | 当前 |
|---|---|---|
| ChatService | 2309 | ~870 |
| ChatSessionActivity | 2281 | ~1940 |
| MessageAdapter | 1054 | ~940 |

三大胖文件最大 1940 行（Activity），仍有空间但 SLOC 不是最重要的指标 —— 行为差异已经物理隔离到 strategy / controller / binder 子文件，改一处不再需要全局搜。
