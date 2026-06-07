# 会话模式 — 加新 mode 的完整指南

> R6 之后所有「模式行为差异」收口到 [session/SessionModeStrategy.kt](../../app/src/main/java/com/example/aichat/session/SessionModeStrategy.kt)。
> 这份文档教你「加一种新的 assistant.type 应该改哪几个文件」。

---

## 1. 现有 3 种模式

| `MyAssistant.type` | `SessionMode` | strategy 文件 | 行为概览 |
|---|---|---|---|
| `""` / 缺省 | `DEFAULT` | [DefaultModeStrategy.kt](../../app/src/main/java/com/example/aichat/session/DefaultModeStrategy.kt) | 普通聊天，所有钩子默认 passthrough |
| `"character"` | `CHARACTER` | [CharacterModeStrategy.kt](../../app/src/main/java/com/example/aichat/session/CharacterModeStrategy.kt) | 角色对话：括号情绪 / 自动 TTS / 不显示 pinned actions |
| `"writer"` | `WRITER` | [WriterModeStrategy.kt](../../app/src/main/java/com/example/aichat/session/WriterModeStrategy.kt) | 作家：大纲按钮 / 注入大纲 prompt / 长助手消息节选 |

---

## 2. SessionModeStrategy 接口

```kotlin
interface SessionModeStrategy {
    val mode: SessionMode

    // 声明式 UI 配置
    val usesCharacterAdapter: Boolean
    val usesWriterAdapter: Boolean
    val disablesAssistantCollapseToggle: Boolean
    val autoFocusLatestOnSetMessages: Boolean
    val hidesPinnedActions: Boolean
    val showsWriterOutlineButton: Boolean
    val supportsAutoTts: Boolean

    // 行为钩子（全部有默认实现，子类按需 override）
    fun buildUserMessageForApi(rawInput: String, ctx: SessionContext): String = rawInput
    fun buildHistoryForApi(source: List<Message>, ctx: SessionContext): List<Message> = source
    fun onOutlineAction(message: Message, host: SessionUiHost): Boolean = false
    fun resolveVoicePlay(message: Message, raw: String): VoicePlayPayload? = null
}
```

子类不持有 Activity 引用、不持 View，全部状态参数化（`ctx: SessionContext`）。这样 strategy 是**纯函数对象**，单测时 mock 容易。

---

## 3. 加新模式（以 myMode 为例）的完整步骤

假设要加 `assistant.type = "myMode"` 表示「小说生成专用模式」，行为是用户输入 → 调 myMode 后端 → 流式返回章节文本。

### Step 1：扩 enum

[session/SessionMode.kt](../../app/src/main/java/com/example/aichat/session/SessionMode.kt) 加一个 case：
```kotlin
enum class SessionMode(val raw: String) {
    DEFAULT(""),
    CHARACTER("character"),
    WRITER("writer"),
    MY_MODE("myMode"),   // ← 新增
}
```
注意：`raw` 字符串值会持久化到 DB（`MyAssistantEntity.type`），定下来就不能改。

### Step 2：写 strategy

新建 [session/MyModeModeStrategy.kt](../../app/src/main/java/com/example/aichat/session/MyModeModeStrategy.kt)：
```kotlin
package com.example.aichat.session

import com.example.aichat.Message

object MyModeModeStrategy : SessionModeStrategy {
    override val mode = SessionMode.MY_MODE
    override val usesCharacterAdapter = false
    override val usesWriterAdapter = true       // 复用 writer 的渲染样式
    override val disablesAssistantCollapseToggle = false
    override val autoFocusLatestOnSetMessages = true
    override val hidesPinnedActions = false
    override val showsWriterOutlineButton = false  // myMode 不用本地大纲
    override val supportsAutoTts = false

    // myMode 的特殊行为放这里
    override fun buildUserMessageForApi(rawInput: String, ctx: SessionContext): String {
        // 例如：拼上当前小说设定
        return rawInput
    }
}
```

### Step 3：在 from() 工厂加一支

[SessionModeStrategy.kt 的 companion `from()`](../../app/src/main/java/com/example/aichat/session/SessionModeStrategy.kt)：
```kotlin
fun from(assistant: MyAssistant?): SessionModeStrategy = when (...) {
    SessionMode.CHARACTER -> CharacterModeStrategy
    SessionMode.WRITER -> WriterModeStrategy
    SessionMode.MY_MODE -> MyModeModeStrategy   // ← 新增
    SessionMode.DEFAULT -> DefaultModeStrategy
}
```

> ⚠️ **这是项目里唯一允许 `when (SessionMode)` 的位置**。其他任何地方出现都是退步。

### Step 4：UI 允许新建 myMode 类型

[EditMyAssistantActivity.kt](../../app/src/main/java/com/example/aichat/EditMyAssistantActivity.kt) 加 radio button：
- res/layout 加一个 `<RadioButton android:id="@+id/typeMyMode" />`
- 加 mapping `R.id.typeMyMode -> "myMode"`
- 加恢复选择的 case：`SessionMode.from(assistant.type) == SessionMode.MY_MODE -> radioType.check(R.id.typeMyMode)`

UI 标签（[HomeAssistantAdapter.kt](../../app/src/main/java/com/example/aichat/HomeAssistantAdapter.kt) / [MyAssistantListAdapter.kt](../../app/src/main/java/com/example/aichat/MyAssistantListAdapter.kt)）加显示 "小说生成"。

### Step 5（如果模式要走不同后端协议）：新建 ChatGenerator 实现

如果 myMode 不走 OpenAI 兼容 API，要新建独立 generator：

```kotlin
// chat/MyModeGenerator.kt
class MyModeGenerator(private val context: Context) : ChatGenerator {
    override fun chat(history, userMessage, options, callback, toolBridge): ChatHandle {
        // 调 myMode HTTP 端点
        // 解析 myMode 协议的流式响应
        // 转成 ChatCallback.onPartial / onSuccess 调用
    }
    override fun generateThreadTitle(firstUserMessage, callback) {
        // myMode 可能没有独立标题端点 → fallback 到默认实现 / 简单截取
    }
}
```

然后让 ChatViewModel 在初始化时按 mode 选 generator：
```kotlin
// ChatViewModel.kt
private val chatService: ChatGenerator = when (resolveSessionMode()) {
    SessionMode.MY_MODE -> MyModeGenerator(application)
    else -> ChatService(application)
}
```

或者引入 [`ChatGeneratorRegistry`](https://github.com/...)（R7 还没做，目前只是单一 ChatService）。

### Step 6：不需要改 ChatSessionActivity

Activity 已经不知道有几种模式 —— 它只调 `mode.xxx` / `chatGenerator.xxx`。加新模式时 Activity 零修改。

---

## 4. 反模式（不要做的事）

❌ **在 Activity 里加 `if (mode == SessionMode.MY_MODE)`**
   → 行为差异应该在 MyModeModeStrategy 里 override 对应钩子

❌ **在 Strategy 之间互相引用**
   → 比如 MyModeModeStrategy 调 WriterModeStrategy。strategy 应该自洽。如果有共享逻辑，抽到 strategy 之外的 helper（如 `OutlinePromptBuilder`）

❌ **strategy 持有 Activity 引用 / View 引用**
   → 通过 `SessionUiHost` 接口反向调用，Host 接口面要保持最小（目前 2 个成员）

❌ **strategy 持有可变状态**
   → 应该是 `object`（单例无状态）。所有状态走 `SessionContext` 参数

❌ **修改 SessionMode.raw 字符串值**
   → 这些值在 DB 里（MyAssistantEntity.type）。改 raw = 现有数据失效

---

## 5. SessionUiHost 何时该扩

当前 SessionUiHost 只有 2 个成员：
```kotlin
interface SessionUiHost {
    val assistantId: String?
    fun summarizeMessageToOutline(message: Message)
}
```

什么时候该加成员？
- strategy 需要让 Activity 做一个具体 UI 动作（如「打开某 dialog」）
- 这个动作没法通过返回值 / SessionContext 表达

不要做：
- 加返回 View 引用的方法
- 加返回大段 mutable state 的方法
- 加只有一种 strategy 用到的方法（应该考虑：是不是设计错了？）

---

## 6. SessionContext 何时扩字段

当前：
```kotlin
data class SessionContext(
    val sessionId: String,
    val assistantId: String?,
    val assistant: MyAssistant?,
    val options: SessionChatOptions,
    val writerOutlineBlock: String = "",
    val writerLastSegmentChars: Int = 1000,
    val writerEarlyExcerptMaxChars: Int = 500,
)
```

加字段时：
- 字段是数据快照（不变值）
- 与某个 strategy 钩子的输入相关
- 默认值合理（其他模式不填也能工作）

例如：myMode 模式需要"当前小说大纲"传给 strategy，可以加 `myModeNovelOutline: String = ""`。

---

## 7. 测试新 strategy

strategy 是 `object` 无状态 → 直接调方法测：

```kotlin
@Test
fun `myMode buildUserMessageForApi 注入小说设定`() {
    val ctx = SessionContext(
        sessionId = "test",
        assistantId = null,
        assistant = null,
        options = SessionChatOptions(),
        myModeNovelOutline = "主角是小明",
    )
    val out = MyModeModeStrategy.buildUserMessageForApi("第二章发生什么？", ctx)
    assertTrue(out.contains("主角是小明"))
}
```

无需 Robolectric / View mock。是这次重构的关键收益之一。

---

## 8. 完整 checklist：加新模式 = 改这几个文件

- [ ] `session/SessionMode.kt` — 加 enum case
- [ ] `session/XxxModeStrategy.kt` — 新建 object
- [ ] `session/SessionModeStrategy.kt` — `from()` 加 case
- [ ] `EditMyAssistantActivity.kt` — UI radio button
- [ ] `res/layout/activity_edit_my_assistant.xml` — radio button view
- [ ] `HomeAssistantAdapter.kt` / `MyAssistantListAdapter.kt` — UI label
- [ ] （可选）`chat/XxxGenerator.kt` + ChatViewModel 选 generator —— 如果走不同后端协议
- [ ] （可选）`SessionContext.kt` 加字段 —— 如果新模式需要新输入
- [ ] （可选）`SessionUiHost.kt` 加方法 + Activity 实现 —— 如果新模式需要 Activity 做新动作

**不需要改**：ChatService、ChatSessionActivity 主流程、MessageAdapter、ChatViewModel.chat() 流程。
