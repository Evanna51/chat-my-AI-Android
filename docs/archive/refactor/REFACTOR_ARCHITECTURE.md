# 拆分架构设计

> 与 [BIG_FILES_REFACTOR_PLAN.md](BIG_FILES_REFACTOR_PLAN.md) 配套使用：
> - 那份是**现状地图**（哪行干什么）+ **执行步骤**
> - 这份是**目标架构**（拆完长什么样）+ **接口契约** + **设计原则**
>
> 动手前先读这份，定位旧代码看那份。
> 最后更新：2026-06-05

---

## 一、为什么必须拆

| 文件 | 行数 | 真实职责数 |
|---|---|---|
| ChatService.kt | 2309 | 8（普通聊天 / 标题 / session 大纲 / 单条总结 / 章纲 JSON / 卷大纲 / 知情约束 / 流式底层） |
| ChatSessionActivity.kt | 2281 | 9（生命周期 / 附件 / 流式 / 打字机 / 章节跳转 / TTS / 模式分支 / Toolbar / Proactive 联动） |
| MessageAdapter.kt | 1054 | 4（普通气泡 / reasoning 折叠 / 角色情绪 / 工具调用） |

**根因不是行数，是 SRP 违背**：单类塞 4–9 种职责，任何一种改动都要扫描全文。

**第二根因**：`MyAssistant.type: String = ""` 是裸字符串。三种模式（default / character / writer）的差异全部表现为 `if (writerAssistant)` / `if (characterAssistant)` 散落各处。模式新增（inkos）= 又加一支 if。

---

## 二、设计原则（不可妥协）

1. **每个类一句话能说清职责**。说不清就拆。
2. **模式差异只允许在一处分支**：`SessionMode.from(type)` 实例化策略后，外部代码不再 `when (mode)`。
3. **公开 API 越窄越好**：能 `internal` 不 `public`；能不暴露字段就不暴露。
4. **依赖单向**：UI → Strategy → Service → Network/DB；不允许 Service 反过来引用 Activity / Adapter。
5. **组合优于继承**：策略类用接口实现 + 组合注入，不用抽象类层级。
6. **流式协议唯一实现**：`streamChat` 不允许被复制 / 包装到其他 service 里。
7. **新增模式 = 新增文件**，不允许修改已有 strategy 的内部分支。

---

## 三、目标架构总览

```
┌──────────────────────────────────────────────────────────────────┐
│ ChatSessionActivity (≤900 行)                                     │
│   - 生命周期 / View 绑定 / 把事件转发给 mode + viewModel            │
│   - 不写 if (writerAssistant) / if (characterAssistant)            │
└────────┬─────────────────────────────────────────────────────────┘
         │ holds
         ▼
┌─────────────────────┐    ┌─────────────────────────────────────────┐
│ SessionModeStrategy │◄───┤ SessionMode (enum)                      │
│  (interface)        │    │  DEFAULT / CHARACTER / WRITER / INKOS   │
└────┬────────────────┘    └─────────────────────────────────────────┘
     │ impl
     ├──► DefaultModeStrategy
     ├──► CharacterModeStrategy   (TTS / 括号情绪 / 开场白 / proactive)
     ├──► WriterModeStrategy      (大纲 / 章节跳转 / chapter plan / 知情约束)
     └──► InkosModeStrategy       (inkos service 接入)

┌──────────────────────────────┐
│ ChatViewModel                 │  (维持现状，移除内部 new ChatService)
│  - 消息分页 / 流式事件队列     │
│  - 由外部注入 ChatGenerator    │
└────────┬─────────────────────┘
         │ depends on
         ▼
┌─────────────────────────────────────────────────────────────────┐
│ ChatGenerator (interface) ← 关键解耦点                            │
│   fun chat(req, cb): Handle                                      │
│   fun cancel(handle)                                             │
└────────┬────────────────────────────────────────────────────────┘
         │ impl
         ├──► OpenAiCompatibleGenerator  (现 ChatService 的 chat + streamChat)
         └──► InkosGenerator             (走 inkos 协议)

┌──────────────────────────────────────────────────────────────────┐
│ Writer 子领域 (writer/)                                            │
│   WriterOutlineService     ← session 大纲 / 单条总结                │
│   WriterChapterPlanService ← chapter plan JSON                    │
│   WriterVolumeService      ← 卷大纲 / 知情约束                      │
│   WriterJsonHelpers        ← repair / normalize（internal）        │
│   全部依赖 ChatGenerator，不依赖 Activity                           │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ adapter/                                                          │
│   MessageAdapter           ← 派发 + 通用绑定 (≤600 行)              │
│   AssistantBubbleBinder    ← reasoning / content                  │
│   ToolCallMessageBinder    ← 工具调用气泡                          │
│   CharacterDisplayRenderer ← 仅 CHARACTER 模式调用                  │
│   CollapseAffixController  ← 折叠按钮跟随                          │
└──────────────────────────────────────────────────────────────────┘
```

**关键性质**：
- `ChatSessionActivity` 不知道有几种模式
- `ChatGenerator` 不知道有几种 UI
- `WriterChapterPlanService` 不知道有 Activity

---

## 四、核心契约（Kotlin 签名草图）

### 4.1 `SessionMode`

```kotlin
enum class SessionMode(val raw: String) {
    DEFAULT(""),
    CHARACTER("character"),
    WRITER("writer"),
    INKOS("inkos");

    companion object {
        fun from(assistantType: String?): SessionMode =
            values().firstOrNull { it.raw == assistantType?.lowercase() } ?: DEFAULT
    }
}
```
**唯一职责**：字符串 ↔ 枚举。**不放业务方法**（不要 `mode.supportsOutline()` 之类，那是 strategy 的事）。

### 4.2 `SessionModeStrategy`

```kotlin
interface SessionModeStrategy {
    val mode: SessionMode

    // 装配阶段（onCreate / 切换助手时）
    fun configureAdapter(adapter: MessageAdapter)
    fun configureToolbar(host: SessionUiHost)
    fun configureBottomBar(host: SessionUiHost)

    // 发送路径
    fun buildUserMessageForApi(rawInput: String, ctx: SessionContext): String
    fun buildHistoryForApi(source: List<Message>, ctx: SessionContext): List<Message>

    // 接收路径
    fun onAssistantMessageDone(message: Message, ctx: SessionContext)

    // 选 generator（让 inkos 走自己的 generator）
    fun generator(registry: ChatGeneratorRegistry): ChatGenerator
}

interface SessionUiHost {       // Activity 暴露给 strategy 的最小面
    fun setWriterOutlineButtonVisible(visible: Boolean)
    fun setAutoTtsButtonVisible(visible: Boolean)
    fun setCharacterAvatar(assistant: MyAssistant?)
    fun showChapterJumpMenu()
    // ... 仅 UI 操作，不暴露 view 引用
}

data class SessionContext(
    val sessionId: String,
    val assistantId: String?,
    val assistant: MyAssistant?,
    val options: SessionChatOptions,
)
```

**关键设计**：
- Strategy **不持有 Activity 引用**，通过 `SessionUiHost` 接口反向调用
- `SessionContext` 是不可变数据快照，避免 strategy 反查 Activity 状态
- 每个钩子有默认实现（`DefaultModeStrategy` 全空实现），其他 strategy 只 override 自己关心的

### 4.3 `ChatGenerator`

```kotlin
interface ChatGenerator {
    fun chat(request: ChatRequest, callback: ChatCallback): ChatHandle
}

interface ChatHandle {
    fun cancel()
    val isCancelled: Boolean
}
```
**意义**：`ChatViewModel` 只依赖这个接口。OpenAI 协议、inkos 协议都实现它，互不影响。

### 4.4 `ChatGeneratorRegistry`

```kotlin
class ChatGeneratorRegistry(
    private val openAi: OpenAiCompatibleGenerator,
    private val inkos: InkosGenerator?,
) {
    fun forMode(mode: SessionMode): ChatGenerator = when (mode) {
        SessionMode.INKOS -> inkos ?: error("inkos not enabled")
        else -> openAi
    }
}
```
**注意**：这是**唯一**允许 `when (mode)` 的地方 —— 在工厂边界。其它任何地方出现 `when (mode)` 都是退步。

---

## 五、模块清单（每个 ≤ 400 行硬指标）

### chat/（核心聊天，模式无关）
| 文件 | 职责 | 行上限 | 依赖 |
|---|---|---|---|
| `ChatService.kt` | 装配 ChatRequest + 委托 streamChat（保留兼容入口） | 600 | OkHttp, ChatApi |
| `ChatStreamClient.kt` | **唯一** streamChat 实现：SSE 解析 / reasoning 抽取 / tool call 累积 | 600 | OkHttp |
| `ChatRequestBuilder.kt` | 模型默认参数注入 + buildMessages | 200 | ModelDefaultParams |
| `ChatTitleGenerator.kt` | 起标题（独立小流程） | 150 | ChatStreamClient |

### writer/（作家专属，纯计算）
| 文件 | 职责 | 行上限 | 依赖 |
|---|---|---|---|
| `WriterOutlineService.kt` | session 大纲 + 单条总结 | 300 | ChatStreamClient |
| `WriterChapterPlanService.kt` | chapter plan JSON 生成 + 容错重试 | 400 | ChatStreamClient, WriterJsonHelpers |
| `WriterVolumeService.kt` | 卷大纲 + 知情约束 | 300 | ChatStreamClient |
| `WriterJsonHelpers.kt` | normalize / repair / extract（internal） | 300 | Gson |

### inkos/（新增）
| 文件 | 职责 | 行上限 | 依赖 |
|---|---|---|---|
| `InkosService.kt` | HTTP 客户端 + DTO + 鉴权 | 300 | OkHttp |
| `InkosGenerator.kt` | 实现 `ChatGenerator`，把 inkos 流转成 ChatCallback | 200 | InkosService |
| `InkosDtos.kt` | 请求 / 响应数据类 | 200 | - |

### session/（Activity 拆分件）
| 文件 | 职责 | 行上限 | 依赖 |
|---|---|---|---|
| `SessionMode.kt` | enum + from() | 50 | - |
| `SessionModeStrategy.kt` | interface + SessionUiHost + SessionContext | 100 | - |
| `DefaultModeStrategy.kt` | 全空 / 透传 | 100 | - |
| `CharacterModeStrategy.kt` | TTS / 括号情绪 / 开场白 / proactive | 300 | CharacterMemoryApi, VolcEngineTTSManager |
| `WriterModeStrategy.kt` | 大纲面板 / 章节跳转触发 / 大纲注入 prompt | 350 | WriterOutlineService, SessionOutlineStore |
| `InkosModeStrategy.kt` | inkos 入口（generator 切换 + UI） | 200 | InkosGenerator |
| `ChapterJumpController.kt` | 章节定位 + 滚动（仅 writer 调用） | 200 | - |
| `ChatAttachmentController.kt` | 文件 / 位置 / pending attachments | 300 | - |
| `StreamTypewriter.kt` | 打字机节奏 + 帧合并 | 200 | Handler |

### adapter/
| 文件 | 职责 | 行上限 | 依赖 |
|---|---|---|---|
| `MessageAdapter.kt` | RV 派发 + 通用绑定 | 600 | - |
| `AssistantBubbleBinder.kt` | reasoning 折叠 + content 绑定 | 250 | - |
| `ToolCallMessageBinder.kt` | 工具调用气泡 | 150 | - |
| `CharacterDisplayRenderer.kt` | 括号情绪渲染 | 100 | BracketEmotionMapper |
| `CollapseAffixController.kt` | 折叠按钮视口跟随 | 150 | - |

**总账**：原 5644 行 → 拆后约 6500–7000 行（含必要的接口 / data class 样板）。**行数会增加，但单文件平均 200 行，可读性远超现在。**

---

## 六、依赖规则（编译期可强约）

```
allowed:
  ui (Activity / Adapter / View)   ──►  session/* (Strategy)
  session/* (Strategy)              ──►  chat/* (ChatGenerator)
  session/* (Strategy)              ──►  writer/* / inkos/*
  writer/* / inkos/*                ──►  chat/* (ChatStreamClient)

forbidden:
  chat/*    ─X─►  Activity / Adapter / Strategy
  writer/*  ─X─►  Activity / Adapter
  Strategy  ─X─►  other Strategy   (DefaultModeStrategy 例外，作为基类不算)
  Adapter   ─X─►  Strategy / Service
```

**实施**：用 [Konsist](https://github.com/lemonappdev/konsist) 或自己写一个 `arch_test.sh`（grep import）锁死。CI 跑一遍。

---

## 七、迁移路径（与 BIG_FILES_REFACTOR_PLAN.md §6 对齐，但更强调验收）

| 步 | 内容 | 验收（必须全过） |
|---|---|---|
| R1 | `SessionMode` enum + `SessionMode.from()`，所有旧字符串 `"writer"` / `"character"` 替换为 `SessionMode.X.raw`（值不变） | `grep '"writer"\|"character"' app/src/main/java/com/example/aichat/` 仅剩 enum 定义处；编译通过 |
| R2 | 抽 `ChatStreamClient` / `ChatRequestBuilder`，`ChatService` 内部委托。**公开 API 不变** | 所有 callers 0 改动；单元测试（如有）全绿 |
| R3 | 抽 `writer/` 四个 service，`ChatService` 中对应方法变成转发壳子（标 `@Deprecated`） | `ChatService.kt` ≤ 800 行 |
| R4 | 抽 adapter/ 子组件 + `ChapterJumpController` + `ChatAttachmentController` + `StreamTypewriter` | `ChatSessionActivity.kt` ≤ 1400 行；`MessageAdapter.kt` ≤ 700 行 |
| R5 | 引入 `ChatGenerator` 接口；`OpenAiCompatibleGenerator` 实现；`ChatViewModel` 改持 `ChatGenerator` | 单元测试可用 fake generator 替换 |
| R6 | 引入 `SessionModeStrategy` + 四个实现；Activity 删除所有 `writerAssistant` / `characterAssistant` 分支 | `grep 'writerAssistant\|characterAssistant'` 0 命中；`when (mode)` 仅在 `ChatGeneratorRegistry` 出现 |
| R7 | 接 `InkosService` + `InkosGenerator` + `InkosModeStrategy` + EditMyAssistantActivity 加单选 | 切换助手到 inkos 类型，发送消息走 inkos 路径，writer/character 路径回归正常 |

**每步独立 commit + `./gradlew assembleDebug` 必须绿**。

---

## 八、反模式清单（拆分时绝对禁止）

1. **新 strategy 内部再 `when (mode)`** —— 那就退化成 if 大坑了
2. **保留旧字段 / 旧方法做兼容** —— Strategy 化后，旧 bool / 旧字符串路径必须删干净（参考 `~/.claude/memory/feedback_git_rules.md` 的"不留兼容 hack"原则）
3. **Strategy 持有 Activity / View 引用** —— 必须通过 `SessionUiHost` 接口
4. **Service 反查 Activity** —— Service 只接受 `SessionContext` 数据快照
5. **writer/* 里调用 OkHttp** —— 必须经过 `ChatStreamClient`
6. **inkos 复用 `ChatService.chat`** —— inkos 是另一种协议，强行融合 = 新 if 大坑
7. **拆分提交里夹带功能改动** —— 拆分 commit 必须保持行为等价，diff 可肉眼对照

---

## 九、可测试性（拆完应该能写的测试）

| 模块 | 可写的测试 |
|---|---|
| `ChatRequestBuilder` | 给定 options + history → 验证 ChatRequest 字段（纯函数） |
| `WriterJsonHelpers` | repair 各种损坏 JSON → 验证产出合法（纯函数，已经有现成 case） |
| `WriterChapterPlanService` | 注入 fake `ChatGenerator` 返回固定字符串 → 验证 normalize 结果 |
| `SessionMode.from` | 各种字符串输入 → 正确枚举 |
| `CharacterModeStrategy` | 注入 fake UiHost → 验证按钮显隐顺序 |
| `ChatGeneratorRegistry` | mode → generator 选择正确 |

**今天这三个胖文件几乎无法写单元测试**，因为依赖 Android Context / View / SQLite。拆完后至少四个纯函数模块可测。

---

## 十、给后续会话的一句话使用说明

> 「按 [REFACTOR_ARCHITECTURE.md](REFACTOR_ARCHITECTURE.md) §四 的接口签名 + §五 的模块行上限 + §八 的反模式做。
> 旧代码定位查 [BIG_FILES_REFACTOR_PLAN.md](BIG_FILES_REFACTOR_PLAN.md) §八 索引表。」
