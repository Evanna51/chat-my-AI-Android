# 模块地图 — 包结构与责任划分

> AI 速查表：知道要改的功能在哪个包。配合 [../AI_GUIDE.md](../AI_GUIDE.md) §3 的胖文件函数索引使用。

---

## 包总览

包路径基底是 `com.example.aichat`。

```
com.example.aichat
├── (根包)                 ← 历史遗留 + Activity / DAO / Entity / 顶层数据类
├── adapter/               ← MessageAdapter 子组件
├── chat/                  ← ChatGenerator 接口 + ChatService 子组件 + proactive 工具
├── proactive/             ← proactive 自动对话调度（WorkManager / ActiveSessionTracker）
├── session/               ← 会话模式策略 + Activity 拆出来的子领域控制器
├── sync/                  ← chat-server 跨端同步 + memory tool + ws + 上下文拉取
├── widget/                ← 自定义 View
└── writer/                ← Writer 模式专属 service + 容错 JSON 工具
```

---

## 根包（顶层文件）

### Activity / Fragment 入口
| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 会话列表 / 起新会话 |
| `ChatSessionActivity.kt` | **会话聊天主屏（最复杂）** ~1940 行 |
| `AllConversationsActivity.kt` | 全部会话浏览 |
| `EditMyAssistantActivity.kt` | 助手编辑 |
| `MyAssistantsActivity.kt` | 助手列表 |
| `ConfigActivity.kt`, `ModelConfigActivity.kt`, `ProviderDetailActivity.kt`, `ProviderListAdapter.kt` | 模型 / Provider 配置 |
| `SessionChatSettingsActivity.kt` | 会话级设置 |
| `SessionOutlineActivity.kt` | Writer 模式大纲编辑 |
| `CharacterInfoActivity.kt` | Character 资料卡 |
| `RemoteSyncSettingsActivity.kt` | 远程同步开关 |
| `GeneralSettingsActivity.kt`, `TTSSettingsActivity.kt`, `SettingsActivity.kt` | 一般设置 |
| `ToolCallLogActivity.kt` | 工具调用日志查看 |

### 核心数据 / 业务类
| 文件 | 职责 |
|---|---|
| `ChatService.kt` | **聊天 service 主入口（实现 ChatGenerator）** ~870 行 |
| `ChatViewModel.kt` | Activity 共享 ViewModel，持有 chatService 字段 + LiveData |
| `MessageAdapter.kt` | **RV adapter（最复杂）** ~940 行 |
| `Message.kt` | 消息领域模型（含 role / proactiveKind / turnId） |
| `MyAssistant.kt` | 助手数据类（含 `type` 字符串字段 → SessionMode） |
| `SessionChatOptions.kt` | 单会话级模型参数（temperature / topP / autoChat 等） |
| `ChatApi.kt` | Retrofit 接口 + 请求/响应 data class |
| `AiModelConfig.kt` | 模型 + provider 配置解析 |
| `ProviderManager.kt`, `ProviderCatalog.kt` | provider 注册表 |

### Room 持久化
| 文件 | 用途 |
|---|---|
| `AppDatabase.kt` | Room DB + migrations |
| `RoomMigrationHelper.kt` | SP → Room 一次性迁移 |
| `MessageDao.kt`, `MessageDao.kt` | 消息表 |
| `SessionMetaDao.kt` + Entity + Store | 会话元数据 |
| `SessionChatOptionsDao.kt` + Entity + Store | 单会话选项 |
| `MyAssistantDao.kt` + Entity + Store | 助手 |
| `SessionAssistantBindingDao.kt` + Entity + Store | 会话↔助手绑定 |
| `SessionOutlineStore.kt`, `SessionOutlineItem.kt` | Writer 大纲 |
| `RelationshipStateDao.kt` + Entity + Store | Character 关系状态 |
| `SessionChatOptionsStore.kt`, `ConfigManager.kt` | 全局配置 SP |

### 工具/辅助
| 文件 | 用途 |
|---|---|
| `EmotionTagParser.kt` | Character `[emotion]` 协议解析 |
| `BracketEmotionMapper.kt` | emoji ↔ SpeechProfile 映射 |
| `AttachmentFileReader.kt` | 附件文件读取 |
| `OutlinePromptBuilder.kt` | Writer 大纲拼成 prompt 块 |
| `ExportUtil.kt` | 会话导出 |
| `MarkdownHelpers.kt`（待出现） | markdown 转换（目前内联在 Activity） |
| `VolcEngineHttpTTS.kt`, `VolcEngineTTSManager.kt` | 火山 TTS |
| `AutoReadStore.kt` | 自动 TTS 开关 SP |

---

## `chat/` — 聊天协议层

| 文件 | 职责 |
|---|---|
| **`ChatGenerator.kt`** | **接口**：chat() + generateThreadTitle()。所有上层（ChatViewModel）只依赖这个接口 |
| `ChatCallback.kt` | 流式回调 interface（onPartial / onReasoning / onSuccess / onToolCallStart 等） |
| `ChatHandle.kt` | 请求句柄（cancel） |
| `ToolMessageRecord.kt` | 工具调用消息持久化数据快照 |
| `ChatTitleGenerator.kt` | 起标题（同步 retrofit，不走 streamChat） |
| `ChatTextHelpers.kt` | 通用文本/JSON 工具（stripThinkTags / parseFirstJsonObject 等 12 个） |
| `ChatJsonHelpers.kt` | 底层防御性 Gson getter（getInt / getString / getStringFlexible） |
| `ChatReasoningExtractor.kt` | reasoning 从 delta JSON 提取 |
| `ChatToolCallAccumulator.kt` | 流式工具调用累积 |
| `ChatInlineThinkProcessor.kt`, `InlineThinkState.kt` | 内联 `<think>` 标签处理 |
| `ChatTimeContext.kt` | "现在是 X 时 Y 分" 上下文行 |
| `ToolCallBuilder.kt` | 构造 OpenAI 协议 tool_calls |
| `ProactiveChatPlanner.kt`, `ProactiveMeta.kt`, `ProactiveMetaParser.kt`, `ProactivePromptBuilder.kt`, `ProactiveBudget.kt`, `ProactiveSplitStreamFilter.kt` | proactive 自动对话工具集 |

**依赖方向**：ChatViewModel → ChatGenerator → ChatService（实现） → chat/* 工具。

---

## `writer/` — Writer 模式专属

| 文件 | 职责 |
|---|---|
| `WriterOutlineService.kt` | session 大纲 + 单条消息 → 大纲条目 |
| `WriterChapterPlanService.kt` | 章纲 JSON 容错生成（含降级 / 关键字 fallback） |
| `WriterVolumeService.kt` | 卷大纲 + 知情约束抽取 |
| `WriterJsonHelpers.kt` | Writer 专属 JSON 工具（normalizeChapterPlanJson 等 13 个） |

**调用方**：`SessionOutlineActivity`（用户在大纲页操作）、`ChatSessionActivity`（写作时 summarize message → 大纲条目）。

**架构原则**：每个 service 都 `class XxxService(private val service: ChatService)` 注入 ChatService 引用，通过 `service.streamChat(...)` 等 internal 方法走唯一流式实现。

---

## `session/` — 会话模式策略 + Activity 子领域

| 文件 | 职责 |
|---|---|
| `SessionMode.kt` | enum DEFAULT / CHARACTER / WRITER + raw 字符串 + `MyAssistant?.mode()` 扩展 |
| `SessionModeStrategy.kt` | 策略接口 + companion `from()` 工厂 + VoicePlayPayload |
| `SessionUiHost.kt` | Activity 暴露给 strategy 的 2-成员接口（minimal surface） |
| `SessionContext.kt` | 不可变数据快照传给 strategy |
| `DefaultModeStrategy.kt` | DEFAULT 模式：全 no-op |
| `CharacterModeStrategy.kt` | CHARACTER 模式：括号情绪 / TTS / 不显示 pinned actions |
| `WriterModeStrategy.kt` | WRITER 模式：注入大纲 / 长助手消息节选 / 大纲按钮 |
| `ChatAttachmentController.kt` | 附件 chip 栏 + 待发送列表 |
| `ChapterJumpController.kt` | 章节快速跳转对话框 + 滚动定位 |
| `StreamTypewriter.kt` | 流式打字机（帧循环 + render throttle） |

**唯一允许 `when (SessionMode)` 的地方** = `SessionModeStrategy.from()` 工厂。其他任何地方出现都是退步。

---

## `adapter/` — MessageAdapter 子组件

| 文件 | 职责 |
|---|---|
| `CharacterDisplayRenderer.kt` | Character 模式括号情绪段染色（object） |
| `CollapseAffixController.kt` | 折叠胶囊跟随 viewport |
| `ToolCallMessageBinder.kt` | 工具调用消息行格式化（object） |

---

## `sync/` — 跨端同步

| 文件 | 职责 |
|---|---|
| `ChatServerApi.kt`, `ChatDtos.kt` | wi-chat-server HTTP 客户端 |
| `WsClient.kt` | server-side WebSocket（接收推送 / 自动对话触发） |
| `MemoryToolApi.kt`, `ToolBridge.kt` | search_memory / correct_memory / web_search tools |
| `SearchMemoryFormatter.kt` | search_memory 结果格式化 |
| `CharacterBootstrapStore.kt` | 角色初始数据 |
| `ChatContextCache.kt` | session context 缓存 |
| `EffectivePromptStore.kt` | 有效 prompt 持久化 |
| `HistoryBackfiller.kt` | 历史回填 |
| `SnapshotUploader.kt` | 消息 snapshot 上传 |
| `SyncQueueDrainer.kt`, `SyncDrainWorker.kt`, `SyncScheduler.kt` | 上传队列 + WorkManager 调度 |
| `RemoteSyncConfigStore.kt` | 远程同步配置（地址 / token） |
| `DeviceIdProvider.kt`, `UuidV7.kt` | 设备标识 / ID 生成 |
| `DefaultAssistantId.kt` | 默认助手 ID |

---

## `proactive/` — Proactive 调度

| 文件 | 职责 |
|---|---|
| `ActiveSessionTracker.kt` | 哪个 session 当前 active（避免给可见会话发自动对话） |
| `ProactiveFollowUpWorker.kt` | WorkManager 触发器（angular session 主动追问） |

---

## `widget/` — 自定义 View

| 文件 | 职责 |
|---|---|
| `TypingDotsView.kt` | "对方正在输入" 三点动画 |
| `MessageActionPopup.kt` | 消息长按弹出的 action 面板 |
| `LiquidGlassView.kt`, `GlassTextInputLayout.kt`, `LabeledGlassTextInputLayout.kt` | 液态玻璃风格组件 |

---

## 依赖规则（编译期可强约）

```
allowed:
  ui (Activity / Adapter / View)      ──► session/* (strategy + controller)
  session/* (strategy)                ──► chat/ (ChatGenerator)
  session/* (strategy)                ──► writer/*
  writer/*                            ──► chat/ChatService (via constructor injection)
  chat/* / writer/*                   ──► sync/* (限同步相关)
  adapter/*                           ──► 根包 (Message / R.id 等)

forbidden:
  chat/*       ─X─►  Activity / Adapter / Strategy
  writer/*     ─X─►  Activity / Adapter
  Strategy A   ─X─►  Strategy B（DefaultModeStrategy 不算 —— 它定义所有默认）
  Adapter      ─X─►  Strategy / Service
  ChatService  ─X─►  Activity（包括所有 *Activity 子类）
```

**禁止反向**：写 service / strategy 时不许 import com.example.aichat 根包里的 Activity 类。

---

## 文件/包行数现状（参考，会漂）

```
$ wc -l app/src/main/java/com/example/aichat/ChatService.kt
870

$ wc -l app/src/main/java/com/example/aichat/ChatSessionActivity.kt
1940

$ wc -l app/src/main/java/com/example/aichat/MessageAdapter.kt
940

$ wc -l app/src/main/java/com/example/aichat/chat/*.kt
~3000 total (12 files)

$ wc -l app/src/main/java/com/example/aichat/writer/*.kt
~1400 total (4 files)

$ wc -l app/src/main/java/com/example/aichat/session/*.kt
~1300 total (10 files)
```

---

## 如何加一个新文件

1. **想清楚归哪个包**（看本文档 § 包总览）
2. 单一职责，新文件 ≤ 400 行
3. 单元可测：参数化输入而非反查 Activity 状态
4. 通过 host 接口反向调用 Activity，不持 View 引用
5. 添加 KDoc 顶部说明：「为什么存在 / 谁用 / 不该做什么」
