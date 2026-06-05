# 三大胖文件拆分计划

> 目标：把 `ChatService.kt` / `ChatSessionActivity.kt` / `MessageAdapter.kt` 按"会话模式"和"职责"拆开。 
> 写这份文档的目的是让后续会话**不必每次重新 grep 5644 行代码**才能动手 —— 直接读这份地图。
> 最后更新：2026-06-05

---

## 0. 总览（不要每次重新数）

| 文件 | 行数 | 主类 | 已知问题 |
|---|---|---|---|
| `ChatService.kt` | 2309 | `class ChatService(context)` | 普通聊天 / 标题生成 / 大纲生成 / 单条总结 / 章纲 JSON / 卷大纲 / 知情约束 / 流式底层 全部塞一起 |
| `ChatSessionActivity.kt` | 2281 | `class ChatSessionActivity : ThemedActivity()` | UI + 附件 + TTS + 章节跳转 + 流式打字机 + 角色 / 作家 / 默认三种模式分支散落 |
| `MessageAdapter.kt` | 1054 | `class MessageAdapter` | 同时承担「普通 chat 气泡」「角色括号情绪」「工具调用折叠」三种渲染 |

---

## 1. 会话模式 = 由 `MyAssistant.type` 字符串决定

定义在 [MyAssistant.kt:10](app/src/main/java/com/example/aichat/MyAssistant.kt):
```kotlin
@JvmField var type: String = "" // default / writer / character
```
**目前是裸字符串，没有 enum / sealed class。** 这是所有 if-branch 散落的根因。

| type | 中文 | 行为差异 |
|---|---|---|
| `""` / `"default"` | 默认 | 普通 chat，纯文本气泡，无大纲/章节 |
| `"character"` | 角色 / 人物 | 启用 `[emotion]` 括号情绪解析、角色头像、自动 TTS、主动推送、不允许折叠 reasoning |
| `"writer"` | 作家 / 小说 | 启用大纲面板按钮、章节跳转、知情约束、章纲 JSON、卷大纲、用户消息附大纲 prompt |
| **`"inkos"` (待加)** | inkos 小说生成 | 接 inkos service；暂未实现 |

外部已经识别这三种的地方（搜过 `assistant.type`）：
- [EditMyAssistantActivity.kt:101-156](app/src/main/java/com/example/aichat/EditMyAssistantActivity.kt) — 单选框
- [HomeAssistantAdapter.kt:87-88](app/src/main/java/com/example/aichat/HomeAssistantAdapter.kt) — 标签文案
- [MyAssistantListAdapter.kt:40-41](app/src/main/java/com/example/aichat/MyAssistantListAdapter.kt) — 标签文案
- [SessionChatSettingsActivity.kt:66](app/src/main/java/com/example/aichat/SessionChatSettingsActivity.kt) — `isWriter`
- [MainActivity.kt:425](app/src/main/java/com/example/aichat/MainActivity.kt) — `isWriter`
- [ChatViewModel.kt:715-738](app/src/main/java/com/example/aichat/ChatViewModel.kt) — `isCharacter` 严格判定 + 时间上下文跳过 writer/novel
- [SessionListAdapter.kt:159](app/src/main/java/com/example/aichat/SessionListAdapter.kt) — `GROUP_WRITER`
- [sync/WsClient.kt:366](app/src/main/java/com/example/aichat/sync/WsClient.kt) — proactive 仅角色

---

## 2. ChatService.kt 地图（2309 行）

### 当前职责分区
| 区域 | 行 | 说明 |
|---|---|---|
| `applyModelDefaultsToRequest*` | 48-130 | 默认参数注入 |
| `fun chat(...)` | 132-296 | **入口 1**：普通聊天（所有模式都走） |
| `fun generateThreadTitle` | 298-419 | 自动起标题 |
| `fun generateSessionOutline` | 421-558 | **writer-only** session 大纲 |
| `fun summarizeMessageForOutline` | 560-684 | **writer-only** 单条消息 -> 大纲条目 |
| `fun generateChapterPlanJson` + `buildChapterPlanUserPrompt` + helpers | 686-1242 | **writer-only** 章纲 JSON 生成 + 容错/降级/repair |
| `fun generateVolumeOutline` | 1243-1357 | **writer-only** 卷大纲 |
| `fun extractKnowledgeConstraints` | 1359-1478 | **writer-only** 知情约束抽取 |
| `private fun buildMessages` | 1480-1572 | 通用消息装配 |
| `private fun streamChat` | 1591-1916 | **流式底层**（被所有 public 入口共用） |
| `logProactiveMetaDebug` 等 jsonish 工具 | 1918-2309 | JSON / 文本工具 |

### 提议拆分
```
chat/
  ChatService.kt              ← 只剩 chat() + streamChat() + buildMessages() + 通用 helpers (~900 行)
  ChatTitleGenerator.kt       ← generateThreadTitle (~140 行)
writer/                        ← 新建子包，所有 writer-only 逻辑收口
  WriterOutlineService.kt     ← generateSessionOutline + summarizeMessageForOutline (~260 行)
  WriterChapterPlanService.kt ← generateChapterPlanJson + buildChapterPlanUserPrompt + 所有 repair/normalize (~560 行)
  WriterVolumeService.kt      ← generateVolumeOutline + extractKnowledgeConstraints (~240 行)
  WriterJsonHelpers.kt        ← normalizeChapterPlanJson / repairJsonCandidate / extractStringByKeys 等 (~200 行)
```
**关键**：`streamChat` 必须留在 `ChatService` 内（或抽成 internal），不能让 writer/* 各自再实现一次流式协议。Writer services 通过构造注入 `ChatService` 调用 `streamChat`。

---

## 3. ChatSessionActivity.kt 地图（2281 行）

### 当前职责分区
| 区域 | 行 | 说明 |
|---|---|---|
| 附件处理 | 122-265 | 文件 / 位置 / pending attachments |
| `characterAssistant` / `writerAssistant` 计算 + 应用 | 341, 435-437, 559-568, 1147-1159, 1704-1719 | **两个 bool 散落各处** |
| `onCreate` | 420-636 | 巨型方法，含 btnWriterOutline 显隐、adapter 配置等 |
| `loadMessages` / `applyMessagesAndTitle` / `splitAndDisplay` | 637-703 | 消息加载/渲染入口 |
| `sendMessageFromText` / `dispatchChatRequest*` | 704-981 | 发送路径 |
| `handleStreamDeltaEvent` | 876-980 | 流式回调 |
| 标题 / 模型副标题 | 982-1230 | toolbar |
| **章节跳转** | 1271-1414, 1440-1446 | **writer-only**：`showChapterJumpDialog` / `buildChapterJumpItems` / `scrollToChapterMessage` / `ChapterJumpItem` |
| First dialogue 预览 / 开场白 | 1421-1461 | **character-only** |
| message actions binding | 1462-1517 | 通用 |
| 自动 TTS / 语音播放 | 1518-1599 | **character-only**（visible = characterAssistant && !writerAssistant） |
| outline 总结 + 自动入大纲 | 1600-1645 | **writer-only** |
| `resolveOutlinePrompt` / `buildUserMessageForApi` / `buildHistoryForApi` | 1635-1703 | writer 路径会改写用户输入 |
| `resolveWriterAssistant` / `resolveCharacterAssistant` | 1704-1719 | 单点真相 |
| Loading placeholder / stop response | 1721-2083 | 通用 |
| 流式打字机 | 2085-2147 | 通用 |
| 自动滚动 | 2148-2185 | 通用 |
| reasoning begin/finish | 2186-2207 | 通用 |
| 加载更早 / proactive event | 2208-2281 | 通用 |

### 提议拆分（保持 Activity 本身存在，把模块委托出去）
```
ChatSessionActivity.kt        ← 只留生命周期 + view 绑定 + 委托 (~900 行)
session/
  SessionMode.kt              ← enum DEFAULT / CHARACTER / WRITER / INKOS（替代 type 字符串）
  SessionModeStrategy.kt      ← interface：onConfigureAdapter / onConfigureToolbar / buildUserMessageForApi / buildHistoryForApi / onAssistantMessageDone / supportsOutline / supportsAutoTts
  DefaultModeStrategy.kt
  CharacterModeStrategy.kt    ← bracket emotion + auto TTS + first dialogue + proactive
  WriterModeStrategy.kt       ← outline button + chapter jump + outline-prompt 注入 + chapter plan trigger
  InkosModeStrategy.kt        ← TODO：接 inkos service（详见 §5）
  ChapterJumpController.kt    ← 章节跳转独立（仅 writer 用）
  ChatAttachmentController.kt ← 附件相关 122-265
  StreamTypewriter.kt         ← 打字机 2085-2147
```
Activity 持有 `mode: SessionModeStrategy`，在 `onCreate` 根据 `resolveAssistantType()` 实例化对应 strategy，删除所有 `if (writerAssistant)` / `if (characterAssistant)` 分支。

---

## 4. MessageAdapter.kt 地图（1054 行）

### 当前职责分区
| 区域 | 行 | 说明 |
|---|---|---|
| Mode 开关 | 65-66, 295-317 | `characterMode` / `writerMode` / `characterAssistant` |
| `setMessages` / `formatToolBuffer` / `parseFirstToolCall` | 328-407 | 工具调用消息折叠 |
| pin/streaming/notifyChanged | 408-484 | 流式回调 |
| `getItemViewType` / `onCreateViewHolder` / `bindViewHolder` 派发 | 485-658 | RV 标准方法 |
| user popup / 最新判定 | 660-708 | actions |
| onViewAttached/Detached | 712-757 | 视口监听 |
| action level cycle / expand | 759-785 | 三段式 action |
| `bindReasoning` | 786-853 | reasoning 折叠 |
| `bindAssistantContent*` | 867-916 | 助手气泡内容 |
| `buildCharacterDisplay` | 917-937 | **character-only** 括号情绪 |
| collapse toggle affix | 938-1015 | 折叠按钮跟随 |
| voice play state | 1016-1054 | **character-only** TTS 指示 |

### 提议拆分
```
adapter/
  MessageAdapter.kt           ← 派发 + 通用绑定 (~600 行)
  AssistantBubbleBinder.kt    ← bindReasoning + bindAssistantContent* (~200 行)
  ToolCallMessageBinder.kt    ← formatToolBuffer + parseFirstToolCall (~80 行)
  CharacterDisplayRenderer.kt ← buildCharacterDisplay + bracket emotion (~80 行) ← 仅 character 模式调
  CollapseAffixController.kt  ← updateCollapseToggleAffix* + applyCollapseToggleAffix (~90 行)
```
character / writer 的差异**不要再走 setCharacterMode/setWriterMode 两个 bool**，改成传 `mode: SessionMode` 一个枚举值。

---

## 5. inkos 小说生成服务接入位

inkos 是新的「小说生成」服务（外部 HTTP 接口），与现有 `"writer"` 是两套东西：
- `writer` = 用 LLM provider 直接产文 + 本地大纲/章纲
- `inkos` = 调用 inkos service 的专用 endpoint，可能是 SSE/HTTP，返回结构化章节

接入点（提议）：
1. 新增 `MyAssistant.type = "inkos"`，`EditMyAssistantActivity` 多一个单选项
2. `SessionMode.INKOS` 走 `InkosModeStrategy`
3. **不要复用 ChatService**：新建 `inkos/InkosService.kt`，独立 HTTP 客户端（接 OkHttp）、独立 DTO
4. `InkosModeStrategy` 接管 `dispatchChatRequest`：拦截后转 inkos，不走 `ChatService.chat()`
5. UI 复用现有气泡，但章节交互复用 `ChapterJumpController`

依赖关系：必须**先完成 §2-§4 的 strategy 化**，再接 inkos —— 否则又会在 if-branch 里再加一支。

---

## 6. 执行顺序（低风险 → 高风险）

每一步独立 PR / commit，跑通 `./gradlew assembleDebug` 再下一步。

| 步骤 | 内容 | 风险 | 预计行数变动 |
|---|---|---|---|
| **R1** | 新建 `SessionMode` enum；不改逻辑，只把 `"writer"` / `"character"` 字符串集中到 enum 的 `fromAssistantType()` | 低 | +60 |
| **R2** | 从 `MessageAdapter` 抽出 `CharacterDisplayRenderer` + `CollapseAffixController`（纯 UI，无业务） | 低 | adapter -180 |
| **R3** | 从 `ChatService` 抽出 `WriterChapterPlanService` + `WriterVolumeService` + `WriterJsonHelpers`（writer-only，不影响默认聊天） | 中 | service -900 |
| **R4** | 从 `ChatService` 抽出 `WriterOutlineService` + `ChatTitleGenerator` | 中 | service -400 |
| **R5** | 从 Activity 抽出 `ChapterJumpController` + `ChatAttachmentController` + `StreamTypewriter` | 中 | activity -400 |
| **R6** | 引入 `SessionModeStrategy` 接口 + 三个实现；Activity 把 `writerAssistant` / `characterAssistant` 分支全部删掉，改成 `mode.xxx()` 调用 | **高** | activity -500，新增 ~600 |
| **R7** | 接入 inkos：新增 `InkosService` + `InkosModeStrategy` | 中（隔离） | 新增 ~400 |

R6 是最难的，但前面 5 步打好底子之后 diff 应该可读。

---

## 7. 红线

1. **不要为兼容旧字符串保留 if-branch**：R6 完成后，全仓搜 `assistant.type ==` / `"writer" ==` / `"character" ==`，所有出现必须改成 `SessionMode` 调用。保留旧逻辑等于白拆。
2. **不要在 writer/ 子包里复制 `streamChat`**：通过 `ChatService` 注入，streamChat 保持单一实现。
3. **不要把 inkos 塞进 `ChatService`**：inkos 是另一套协议，强行融合就是新的 if 大坑。
4. **每步必须可独立编译**：拆分中间态不允许「半个 PR」。
5. **`.claude/worktrees/` 不要 commit**（见 `~/.claude/memory/feedback_claude_worktree.md`），用 `git add <file>` 逐个加。

---

## 8. 给后续会话的快速索引

> 下次再问"ChatService 哪里改"，直接看这张表，不要再让我重新 grep。

| 关键词 | 文件:行 |
|---|---|
| 流式底层 | [ChatService.kt:1591](app/src/main/java/com/example/aichat/ChatService.kt#L1591) `streamChat` |
| 普通聊天入口 | [ChatService.kt:132](app/src/main/java/com/example/aichat/ChatService.kt#L132) `chat` |
| 章纲 JSON | [ChatService.kt:686](app/src/main/java/com/example/aichat/ChatService.kt#L686) `generateChapterPlanJson` |
| Session 大纲 | [ChatService.kt:421](app/src/main/java/com/example/aichat/ChatService.kt#L421) `generateSessionOutline` |
| 卷大纲 | [ChatService.kt:1243](app/src/main/java/com/example/aichat/ChatService.kt#L1243) `generateVolumeOutline` |
| 知情约束 | [ChatService.kt:1359](app/src/main/java/com/example/aichat/ChatService.kt#L1359) `extractKnowledgeConstraints` |
| 角色 / 作家判定 | [ChatSessionActivity.kt:1704](app/src/main/java/com/example/aichat/ChatSessionActivity.kt#L1704) `resolveWriterAssistant` / `resolveCharacterAssistant` |
| writer 大纲注入 prompt | [ChatSessionActivity.kt:1646](app/src/main/java/com/example/aichat/ChatSessionActivity.kt#L1646) `buildUserMessageForApi` |
| 章节跳转 | [ChatSessionActivity.kt:1271](app/src/main/java/com/example/aichat/ChatSessionActivity.kt#L1271) `showChapterJumpDialog` |
| 角色括号情绪渲染 | [MessageAdapter.kt:917](app/src/main/java/com/example/aichat/MessageAdapter.kt#L917) `buildCharacterDisplay` |
| TTS 自动播放 | [ChatSessionActivity.kt:1518](app/src/main/java/com/example/aichat/ChatSessionActivity.kt#L1518) `maybeAutoReadAssistantMessage` |
| MyAssistant.type 字段 | [MyAssistant.kt:10](app/src/main/java/com/example/aichat/MyAssistant.kt#L10) |
| 已有 enum 不要重复造 | `MainActivity.kt:48 HomeTab`, `ModelConfig.kt:17 Scene` |
