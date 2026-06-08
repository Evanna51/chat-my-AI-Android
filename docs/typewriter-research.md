# StreamTypewriter 改造调研

> 调研对象：`app/src/main/java/com/example/aichat/session/StreamTypewriter.kt`
> 调研时间：2026-06-06
> 调研人：Claude（subagent）
> 目标：给 Evanna 一份可决策的方案对比，明天选一条迁移路径。

---

## 已落地修复（截至 2026-06-08）

### P1：作家模式长文流式 UI 卡死（commit `13d7ebf`）

**根因**：`MessageAdapter.bindAssistantContentStreaming` 在展开态每 16ms 调一次 `setText(fullContent)`，TextView 每帧做 O(n) 全文 layout，5000+ 字的章节会把主线程撑爆。

**修复**：
- `AssistantHolder` 新增 `streamingRenderedLength: Int = 0`，记录上次渲染到哪个字符位置
- 首次渲染：`textContent.text = content`（必要的全文 layout，仅一次）
- 后续帧：`textContent.append(content.substring(rendered))`（增量 4 字符，远小于 O(n)）
- `fullBind` 时重置 `streamingRenderedLength = 0`，保证 rebind 后从头渲染

**文件**：`app/src/main/java/com/example/aichat/MessageAdapter.kt`

### P2：多章节后帧率下降（commit 当前分支）

**根因**：`CHARS_PER_FRAME = 4` 在 60fps 下每秒 250 次 `append()` + `ArrayList(attachedAssistantHolders)` snapshot 拷贝 + `pendingChars.substring()` 字符串分配，高频小对象制造 GC 压力。章节越多、积累文本越长，每次 `append()` 后 Android `DynamicLayout` 重计算耗时越明显。

**修复**：`CHARS_PER_FRAME: 4 → 8`，每秒帧循环次数减半（250 → 125 次），主线程 layout/GC 压力降低 50%。500 cps 对阅读体验无影响。

**文件**：`app/src/main/java/com/example/aichat/session/StreamTypewriter.kt`

### P3：大纲页面不实时刷新（commit 当前分支）

**根因**：`SessionOutlineActivity` 依赖 `onResume()` 的 `refreshList()`，但工具操作（StoryStateSync / BookGenerator / ChapterToOutlineSync）在后台线程通过 `SessionOutlineStore`（SharedPreferences）写数据时，页面仍在前台，`onResume` 不会再触发。

**修复**：注册 `SharedPreferences.OnSharedPreferenceChangeListener`，监听 `"aichat_session_outlines"` 的 `"outlines_$sessionId"` key 变化，自动调 `refreshList()`。在 `onStart` 注册、`onStop` 注销，覆盖所有"页面可见"期间的写操作。

**文件**：`app/src/main/java/com/example/aichat/SessionOutlineActivity.kt`

---

## 待解决 / 已知未修复

### ① `applyMessagesFully` fallback 仍在

当流式 holder 滚出视口（如用户往上翻看历史），`renderStreamingMessageIfVisible` 返回 false，`StreamTypewriter` 回退到 `host.applyMessagesFully()`。这会触发 Activity 重建整个消息列表（包括 5-6 条 3000 字章节的 TextView 重 layout），在 handler 帧速率下重复执行，是剩余卡顿的主要来源。

解法方向：R10.7 的 payload-only `notifyItemChanged`（见第 6 节迁移 outline）。

### ② 极长章节 TextView layout 仍是 O(n)

即使 `append(8 chars)` 本身很快，`DynamicLayout` 在字符超过 ~10,000 时仍需从插入点往后重排所有行，理论上是 O(n - insert_pos)。终极解法：R10.4+R10.5 的 ViewModel StateFlow + Choreographer，让字符以 60fps VSynced 速率出，而不是 Handler 估算帧边界。

### ③ target 双轨 / cancel 路径仍混乱

见第 0 节诊断要点 2、4。未进 R10，留给完整迁移。

---

## 0. 现状诊断

现有 `StreamTypewriter` 的核心是 `Handler.postDelayed` 双循环：

- **typewriterRunnable** — 16 ms 一帧，每帧吃 `pendingChars` 头部 4 个字符，拼到 `streamingTargetMessage.content` 上，调 adapter 的 `renderStreamingMessageIfVisible` 做 partial 重绘。
- **renderRunnable** — 等待队列 ≥ 80 字符时触发节流 fallback（24 ms / 48 ms），走 `host.applyMessagesFully` 做整列重装。
- **drainPendingTo / flushNow / stop(clearPending)** — 三套 cancel/收尾路径，互相之间语义微妙：drain 不停 render，flush 不动 pending，stop(true) 直接丢字符。

**用户感觉"和 activeStreamingMessage 总有一些感觉不对"的几个真实成因**：

1. **节奏僵硬 / 不感知到达速率**。`4 char / 16 ms` 是硬常数，等于固定 250 cps。LLM 突发吐 200 字符 chunk 时打字机会 800 ms 才追上；LLM 慢吐时打字机却继续以 250 cps 空跑直到队列见底，节奏一会儿"赶"一会儿"愣"。
2. **target 与 activeStreamingMessage 双轨**。打字机内部 `streamingTargetMessage` 是 Message 引用，Activity 同时持有 `activeStreamingMessage`。两者通过 `setTarget` / `enqueueDelta` 手动同步，任何一处忘记调，content 就会写到错的对象上 —— 这是"耦合感觉怪"的真正来源（不是性能，是状态机不收敛）。
3. **直接 mutate Message.content**。打字机把 `targetMsg.content = old + delta` 写在 model 对象上，UI 和数据共用同一份可变状态，无法回放、无法 diff、cancel 时 model 已被污染。
4. **Cancel 路径 N 选 1**。`flushNow` / `stop(true)` / `stop(false)` / `drainPendingTo` 四套出口，调用方很难记住"流正常结束该调哪个，用户点 stop 该调哪个"。
5. **Handler vs Choreographer**。`postDelayed(16ms)` 不与 VSync 对齐，每帧可能错过 0–16 ms，肉眼能感受到字符间距不均匀；这是大厂打字机普遍走 `Choreographer.postFrameCallback` 的原因（见方案 D）。
6. **partial render 失败回退到 applyMessagesFully**。一旦目标 holder 滚出视口，整列重绘成本与 token 速率同频 —— 等于打字机自己制造了滚动卡顿。

下面列出 5 个方案，按"改动成本由小到大"排序。

---

## 1. 方案 A — GetStream `StreamingText`（Compose 官方参考实现）

**出处**：[GetStream/stream-chat-android-ai](https://github.com/GetStream/stream-chat-android-ai) ·
[`StreamingText.kt` 源码](https://github.com/GetStream/stream-chat-android-ai/blob/develop/stream-chat-android-ai-compose/src/main/kotlin/io/getstream/chat/android/ai/compose/ui/component/StreamingText.kt)

### 核心思路

GetStream 把"队列 + 帧循环"全部消灭了，改用 **Compose `LaunchedEffect` + `delay()` 的协程驱动**。
关键设计：

- **以"完整目标文本"为输入**，不维护 delta 队列。每次外部传进来的 `text` 都是 LLM 至今累计的全文。
- `LaunchedEffect(text, animate)` 监听文本变化，根据 `previousText` 与新 `text` 的关系分三种情况：
  - 新对话 → 从 0 开始重放；
  - **继续 (`text.startsWith(previousText)`)** → 从 `displayedText` 接着往下吐；
  - 无变化 → 仅更新 tracking。
- **按"词 + 空白块"切分**（`Regex("(\s+|\S+)")`），不是按 char。每次 `delay(chunkDelayMs=30)` 后 append 一个 chunk。
- Cancel 自动靠 Compose 生命周期：composable 离开 composition 时 `LaunchedEffect` 协程被取消，无需手动 stop/flush。

### 关键代码（30 行）

```kotlin
@Composable
fun StreamingText(text: String, animate: Boolean = true, chunkDelayMs: Long = 30, ...) {
    var displayedText by remember { mutableStateOf("") }
    var previousText by remember { mutableStateOf("") }

    LaunchedEffect(text, animate) {
        when {
            text.isEmpty() -> { displayedText = ""; previousText = text }
            previousText.isEmpty() || !text.startsWith(previousText) -> {
                displayedText = ""; previousText = text
                animateNewContent("", text, chunkDelayMs) { displayedText = it }
            }
            text.length > previousText.length -> {
                previousText = text
                animateNewContent(displayedText, text, chunkDelayMs) { displayedText = it }
            }
        }
    }
    content(displayedText)
}

private suspend fun animateNewContent(currentText: String, fullText: String,
                                       chunkDelayMs: Long, onUpdate: (String) -> Unit) {
    val newContent = fullText.substring(currentText.length)
    val chunks = splitIntoWords(newContent)
    val builder = StringBuilder(currentText)
    for (chunk in chunks) {
        builder.append(chunk)
        onUpdate(builder.toString())
        delay(chunkDelayMs)
    }
}
```

### 性能特点 / 适用场景

- 协程 cancel 即停，无 leak。
- **Compose-only**。chatbox-android 当前是 View 系统 RecyclerView，**不直接可用**，需要包一层。
- 按 word 切而不是按 char 切：中文场景退化为 "整段一次出现"（中文一句话基本没有 ASCII 空白），需要改 splitter。
- 输入是"完整 text"而不是 delta —— 解决了"target 与 activeStreamingMessage 双轨"问题：消息内容由 ViewModel 持有，打字机是纯渲染层。

### vs 现有实现

| 维度 | 现有 | GetStream |
|---|---|---|
| target 耦合 | Activity / Typewriter 双 ref，手动同步 | 单向数据流，输入即 text，无 target 状态 |
| cancel 出口 | 4 套 | 1 套（协程 cancel） |
| 节奏 | 4 char/16 ms 硬编码 | chunk/30 ms，可配 |
| 速率自适应 | 无 | 无 |
| Cancel 时 model 状态 | 已 mutate | 保持原状（displayed 与 source 分离） |

能解决"耦合感觉怪"的核心问题，但**节奏仍是固定的**（不自适应 LLM 速率），且按 word 切对中文不友好。

---

## 2. 方案 B — flowtoken 的"移动平均自适应速率"算法

**出处**：[Ephibbs/flowtoken](https://github.com/Ephibbs/flowtoken)（React 库，但算法可移植）·
[Vercel AI 讨论 #2391](https://github.com/vercel/ai/discussions/2391)

### 核心思路

flowtoken 解决的是方案 A 不解决的问题：**LLM 速率波动（thinking 模式经常爆发式吐 chunk）时，打字机怎么自适应**。
算法（从 README + npm 包 `SmoothText` props 推断）：

1. 记录最近 N 个 token（默认 `windowSize=10`）的到达时间。
2. **平均到达间隔 = 最近 N 个 token 时间差求平均**。
3. **per-char delay = 平均到达间隔 × delayMultiplier ÷ 平均 token 字符长度**。
4. 这样：
   - 网络突发吐 chunk → 平均间隔变小 → delay 变小 → 打字机加速消化；
   - 网络慢 → 平均间隔变大 → delay 变大 → 打字机也变慢；
   - 整体保持"打字机刚好追上 LLM 速度"的稳态。

### 关键代码（移植到 Kotlin 的伪实现，~35 行）

```kotlin
class AdaptiveTypewriterClock(
    private val windowSize: Int = 10,
    private val delayMultiplier: Float = 1.1f,  // 略慢于到达速率，避免追到队尾空转
) {
    private val arrivalTimestamps = ArrayDeque<Long>()   // ms
    private val arrivalLengths = ArrayDeque<Int>()       // chars per chunk

    fun onTokenArrived(chunkLength: Int) {
        val now = SystemClock.uptimeMillis()
        arrivalTimestamps.addLast(now)
        arrivalLengths.addLast(chunkLength)
        if (arrivalTimestamps.size > windowSize) {
            arrivalTimestamps.removeFirst(); arrivalLengths.removeFirst()
        }
    }

    /** 返回当前每字符应该等待多少 ms（给 typewriter 帧循环用） */
    fun perCharDelayMs(): Long {
        if (arrivalTimestamps.size < 2) return 30L  // 默认值
        val span = arrivalTimestamps.last() - arrivalTimestamps.first()
        val totalChars = arrivalLengths.sum().coerceAtLeast(1)
        val msPerChar = (span.toFloat() / totalChars * delayMultiplier).toLong()
        return msPerChar.coerceIn(8L, 120L)  // 上下限防止飞起 / 卡死
    }
}
```

### 性能特点 / 适用场景

- 与现有 Handler 帧循环 100% 兼容：把 `FRAME_MS` 和 `CHARS_PER_FRAME` 替换为 `perCharDelayMs()` 计算结果即可。
- ArrayDeque + 求和，O(1) amortized，无额外内存压力。
- **真正解决"速率不均匀"的肉眼感**，对 thinking 模式（先沉默 5s 然后一口气吐 500 char）尤其有效。

### vs 现有实现

完全不动整体架构，只是把固定 `4 char / 16 ms` 换成自适应。**但 target 耦合 / cancel 路径混乱的问题不解决**。

适合作为方案 A/D/E 的子组件，而不是独立终态。

---

## 3. 方案 C — Choreographer.postFrameCallback + 时间累加器

**出处**：[Android Developers — Choreographer](https://developer.android.com/reference/android/view/Choreographer) ·
[androidperformance.com — Choreographer 渲染流程](https://androidperformance.com/en/2025/03/26/Android-Perfetto-05-Chorergrapher/)

### 核心思路

把 `Handler.postDelayed(FRAME_MS=16)` 换成 `Choreographer.postFrameCallback`，**与 VSync 对齐**。
关键 win：

- `postDelayed(16ms)` 是"至少 16 ms 后唤醒"，但执行可能落在帧周期的任意点 → 字符可能 16 ms 出一个、也可能 32 ms 一个 → 肉眼能感到的不均匀。
- `Choreographer.FrameCallback.doFrame(frameTimeNanos)` 在 VSync 信号到达时被调用，**所有动画在同一帧观察到同一个 `frameTimeNanos`**。基于这个时间戳计算"距上次渲染流逝了多少 ms" → 决定本帧吐多少字符 → 节奏稳定。

```kotlin
private val frameCallback = object : Choreographer.FrameCallback {
    private var lastFrameNs = 0L
    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameNs == 0L) { lastFrameNs = frameTimeNanos }
        val deltaMs = (frameTimeNanos - lastFrameNs) / 1_000_000L
        lastFrameNs = frameTimeNanos

        // 自适应：本帧应该吐多少字符 = deltaMs / perCharDelayMs
        val perChar = clock.perCharDelayMs()  // 可以接方案 B
        val charsThisFrame = (deltaMs / perChar).toInt().coerceAtLeast(1)
        consumeChars(charsThisFrame)

        if (pendingChars.isNotEmpty()) Choreographer.getInstance().postFrameCallback(this)
    }
}
```

### 性能特点 / 适用场景

- VSync 对齐 → 字符出现的视觉节奏与屏幕刷新同步，肉眼显著平滑。
- 帧丢失（GC、系统忙）时，下一帧 `deltaMs` 会变大，自动一次性多吐几个字符 → 不"卡顿后慢慢追"。
- 改动量：只是把 typewriterRunnable 替换成 frameCallback，相关 schedule/cancel 函数等价替换。

### vs 现有实现

解决"节奏不均匀"，**不解决"耦合感"和 cancel 路径混乱**。最适合作为"先小步迁移"的第一阶段，搭配方案 B 做出来效果立刻可感知。

---

## 4. 方案 D — ViewModel `StateFlow` + Channel 队列，View 层只 collect

**出处**：[Android Developers — Kotlin flows on Android](https://developer.android.com/kotlin/flow) ·
[lambiengcode/compose-chatgpt-kotlin-android-chatbot](https://github.com/lambiengcode/compose-chatgpt-kotlin-android-chatbot) ·
通用 MVI 模式

### 核心思路

把打字机从 Activity / View 层完全抽走，搬到 ViewModel：

- ViewModel 暴露 `val streamingMessage: StateFlow<Message?>`，内容字段就是"当前应显示的文本"。
- ViewModel 内部用 `Channel<String>` 接收来自 ChatService 的 delta，单独 coroutine 在 IO/Default 上以自适应节奏（方案 B）`channel.receive() → delay → stateFlow.update { it.copy(content = ...) }`。
- Activity 只 `repeatOnLifecycle { vm.streamingMessage.collect { msg -> adapter.notifyMessageChanged(msg) } }`，**完全不知道有"target"、"pending"、"flush"这些概念**。
- Cancel = `viewModelScope.cancel()` 或 `channel.close()`，单一出口。

### 关键代码（~40 行）

```kotlin
class ChatViewModel : ViewModel() {
    private val deltaChannel = Channel<String>(Channel.UNLIMITED)
    private val _streamingMessage = MutableStateFlow<Message?>(null)
    val streamingMessage: StateFlow<Message?> = _streamingMessage.asStateFlow()
    private val clock = AdaptiveTypewriterClock()  // 方案 B
    private var typewriterJob: Job? = null

    fun startStream(initial: Message) {
        _streamingMessage.value = initial.copy(content = "")
        typewriterJob = viewModelScope.launch(Dispatchers.Default) {
            val buffer = StringBuilder()
            while (isActive) {
                // 拉到一批 delta；为空就 await，但只等一个就立刻进入打字
                val first = deltaChannel.receiveCatching().getOrNull() ?: break
                buffer.append(first); clock.onTokenArrived(first.length)
                // 非阻塞排空：把已到达的 delta 全聚合再开始吐
                while (true) {
                    val more = deltaChannel.tryReceive().getOrNull() ?: break
                    buffer.append(more); clock.onTokenArrived(more.length)
                }
                // 按自适应节奏吐
                while (buffer.isNotEmpty() && isActive) {
                    val ch = buffer[0]; buffer.deleteCharAt(0)
                    _streamingMessage.update { it?.copy(content = (it.content ?: "") + ch) }
                    delay(clock.perCharDelayMs())
                }
            }
        }
    }
    fun onDelta(delta: String) { deltaChannel.trySend(delta) }
    fun finishStream() { deltaChannel.close() }
    fun cancelStream() { typewriterJob?.cancel(); deltaChannel.close() }
}
```

### 性能特点 / 适用场景

- **彻底消除 target 双轨问题**：UI 状态唯一真源是 `StateFlow.value.content`，Activity 不持有任何 mutable 字段。
- Cancel 出口只有 `cancelStream()` 一个，语义清晰。
- 消息是 `copy(content=...)` 不可变，**model 不再被 mutate**，便于做"撤回到流开始前"的功能。
- 缺点：跨进程生命周期（旋转、后台杀）后 streaming 状态自动恢复仍然需要额外持久化；不过这本来就是问题。
- 与 RecyclerView 衔接：Activity collect 到新 message → `adapter.notifyItemChanged(pos, TextDeltaPayload)`，**配合 payload 仍然走 partial render**（见参考 [Domen Lanišnik — RecyclerView payloads](https://medium.com/@domen.lanisnik/efficiently-updating-recyclerview-items-using-payloads-1305f65f3068)）。

### vs 现有实现

这是**架构层面的方案**，最彻底但工作量也最大：要把 `ChatSessionActivity` 里所有触碰 `activeStreamingMessage` 的地方改成读 `StateFlow`。R-series 重构（chatbox-android 当前进度：StreamTypewriter、AttachmentController、ChapterJumpController、ToolCallMessageBinder 等都已抽出）的下一步正好该轮到 streaming 状态本身。

---

## 5. 方案 E — Accumulator + 固定间隔 Flush（Muddy Terrain / TypeIt 模式）

**出处**：[Muddy Terrain — Optimizing UI for Streaming LLMs](https://muddyterrain.com/blog/optimize-ui-streaming-llm-unreal-engine) ·
[Alex MacArthur — Streaming text with TypeIt](https://macarthur.me/posts/streaming-text-with-typeit/)

### 核心思路

不追求 char-by-char 出字幕，**接受"以 100 ms 为周期把累计文本一次性灌给 TextView"** —— 大幅简化所有问题：

- 两个变量：`accumulatedText`（网络 callback 直接 append）和 `displayedText`（UI 持有）。
- 单一 timer（100 ms loop）：如果 `accumulatedText != displayedText`，`textView.setText(accumulatedText)`。
- 没有队列、没有 cancel 复杂度、没有 target 双轨。

```kotlin
private val accumulated = StringBuilder()
private var displayed = ""
private val flushRunnable = object : Runnable {
    override fun run() {
        val now = accumulated.toString()
        if (now != displayed) {
            displayed = now
            adapter.notifyItemChanged(streamingPos, TextDeltaPayload(now))
        }
        if (streaming) mainHandler.postDelayed(this, 100)
    }
}
fun onDelta(d: String) { accumulated.append(d); ensureFlushScheduled() }
fun stop(commit: Boolean) { streaming = false; if (commit) flushRunnable.run() }
```

### 性能特点 / 适用场景

- 极简，几乎零 bug surface。
- **不是真正的打字机**，是"每 100 ms 翻一页"——视觉上是"chunk-by-chunk reveal"。
- 对中文友好（无需切词），对长 markdown 友好（无中间态半截语法）。
- 但失去"打字感"，体验更像 Claude.ai 的渲染节奏，而不是 ChatGPT 的逐字。

### vs 现有实现

如果用户实际抱怨的是"节奏有问题 + 经常卡"，方案 E 反而是终极解药 —— 把"打字机"目标本身降级。
但如果用户其实想要"更顺的打字感"，方案 E 是反方向。

---

## 6. 推荐方案

**首选：方案 D（ViewModel + StateFlow）+ 方案 B（自适应速率） + 方案 C（Choreographer）的组合。**
理由：

1. 用户原话"和 activeStreamingMessage 总有一些感觉不对"指向的是**状态机不收敛**（target 双轨、cancel 4 套出口、model 被 mutate），不是单纯的节奏问题。只有方案 D 能消除这个根因。
2. 方案 B 解决"节奏不均匀"——这是 LLM 应用通用痛点，几乎所有 2025 后的 LLM UI 库都在做（flowtoken / GetStream / Vercel ai-sdk discussion）。
3. 方案 C 解决"字符间距肉眼不均"——这是 VSync 对齐的标准红利，零额外代价（只是 API 替换）。
4. 方案 A 不选，因为是 Compose-only；现有代码是 View 系统。
5. 方案 E 不选，因为放弃了打字感本身，跟用户期望反向。
6. R 系列重构正好走到 Activity 拆分的尾声，下一步本来就该处理 streaming 状态归属（`ChatSessionActivity` 还在持有 `activeStreamingMessage`）—— 时机合适。

### 迁移 outline（step-by-step，**不是代码**）

> 目标：把 `StreamTypewriter` + `activeStreamingMessage` 两块共同重构成 `ChatViewModel.streamingMessage: StateFlow<Message?>`，并接上自适应速率 + Choreographer。

**Step R10.1 — 抽 `StreamingMessageState` 数据类。**
新建 `data class StreamingState(val message: Message, val targetContent: String, val displayedContent: String)`。`targetContent` = LLM 累计真实文本，`displayedContent` = 当前打字机显示到的位置。

**Step R10.2 — 在 ViewModel 持有 `MutableStateFlow<StreamingState?>`。**
当前 `ChatSessionActivity.activeStreamingMessage` 全部读 / 写改为 `viewModel.streamingState`。Activity 只 collect，不再持有 ref。

**Step R10.3 — 把 ChatService 的 onDelta 接到 ViewModel 的 `appendDelta(delta: String)`。**
方法内部：`update { it.copy(targetContent = it.targetContent + delta) }`。**不再调 typewriter.enqueueDelta**。

**Step R10.4 — 新建 `TypewriterClock`（方案 B）。**
独立类，只负责 `onTokenArrived(int) → perCharDelayMs(): Long`，无 Android 依赖，方便单测。

**Step R10.5 — 新建 `TypewriterEngine`（方案 C + D）。**
- 内部一个 coroutine（在 `viewModelScope`）+ 一个 Choreographer FrameCallback bridge（通过 `withContext(Dispatchers.Main) { Choreographer.getInstance().postFrameCallback(...) }`）。
- 每帧读 `streamingState.value.targetContent.length - displayedContent.length` 得到剩余字符数。
- 按 `TypewriterClock.perCharDelayMs()` 和 frame deltaMs 算"本帧吐几个 char"。
- `update { it.copy(displayedContent = it.displayedContent + delta) }`。

**Step R10.6 — Activity 接 collect。**
```
lifecycleScope.launch {
    repeatOnLifecycle(STARTED) {
        viewModel.streamingState.collect { state ->
            state?.let { adapter.notifyItemChanged(posOf(it.message), TextDeltaPayload(it.displayedContent)) }
        }
    }
}
```
不再调 `setTarget` / `enqueueDelta` / `flushNow` / `drainPendingTo` / `stop`。

**Step R10.7 — 改 `MessageAdapter`。**
新增 `payload-aware onBindViewHolder`：payloads 里有 `TextDeltaPayload` 就只 setText，否则走完整 bind。**这一步直接把 `applyMessagesFully` fallback 路径杀掉** —— payload 机制保证 partial render 永不失败。

**Step R10.8 — Cancel 路径合并。**
- 用户点 stop → `viewModel.cancelStream()` → 内部 `engine.stop(); streamingState.update { it.copy(displayedContent = it.targetContent) }`（一次性显示完已收到的）。
- 流自然结束 → `viewModel.finishStream()` → 内部等 engine drain 完 → `streamingState.value = null` → Activity collect 到 null → adapter rebuild 一次让消息变成"已完成"态。
- 四个出口压成两个：cancel / finish。

**Step R10.9 — 删 `StreamTypewriter.kt`。**

**Step R10.10 — 加单元测试。**
- `TypewriterClock` 纯逻辑测：模拟 chunk arrival 序列，验证 `perCharDelayMs()` 输出在合理范围。
- `TypewriterEngine` 用 `TestCoroutineDispatcher`，模拟 60 fps 帧推进 + delta 注入，验证 displayedContent 单调递增、最终等于 targetContent、cancel 后停止推进。

**预计工作量**：R10.1 ~ R10.10 大约 1.5 天纯 coding + 0.5 天回归。

**风险点**：
- Choreographer + viewModelScope 桥接需要写一个 `awaitFrame()` suspend 函数（参考 `withFrameNanos` —— Compose 已经有，可直接用）。如果不想引 Compose runtime，自己 wrap 一个 `suspendCancellableCoroutine + postFrameCallback` 即可。
- partial render 改 payload-only 后，注意 `notifyMessageChanged` 现存其它 caller（比如工具调用块、状态切换）要分别处理 payload 类型。
- 旋转屏幕 / 后台杀进程：`StateFlow` 在 `viewModelScope` 里活着，但 Activity 重建时 `displayedContent` 会从 ViewModel 直接拿到（不需要重放），这是 ViewModel 方案的 free win。

---

## 7. 参考链接汇总

- [GetStream/stream-chat-android-ai (StreamingText.kt)](https://github.com/GetStream/stream-chat-android-ai/blob/develop/stream-chat-android-ai-compose/src/main/kotlin/io/getstream/chat/android/ai/compose/ui/component/StreamingText.kt)
- [Ephibbs/flowtoken — React smooth streaming](https://github.com/Ephibbs/flowtoken)
- [Vercel AI SDK 讨论 #2391 — FlowToken 入场](https://github.com/vercel/ai/discussions/2391)
- [Muddy Terrain — Optimizing UI for Streaming LLMs in UE5](https://muddyterrain.com/blog/optimize-ui-streaming-llm-unreal-engine)
- [Alex MacArthur — Streaming Text Like an LLM with TypeIt](https://macarthur.me/posts/streaming-text-with-typeit/)
- [Minsang Choi — Recreating ChatGPT iOS App](https://minsangchoi.com/blog/re-creating-chatgpt-ios-app-using-openai-api-(2))
- [Android Developers — Choreographer](https://developer.android.com/reference/android/view/Choreographer)
- [androidperformance.com — Choreographer 渲染流程](https://androidperformance.com/en/2025/03/26/Android-Perfetto-05-Chorergrapher/)
- [Android Developers — Animate text character-by-character (Compose)](https://developer.android.com/develop/ui/compose/quick-guides/content/animate-text)
- [Kappdev — Typewriter with Animatable](https://medium.com/@kappdev/crafting-typewrite-text-animation-custom-quote-card-with-jetpack-compose-92ab76582efb)
- [Domen Lanišnik — RecyclerView partial updates with payloads](https://medium.com/@domen.lanisnik/efficiently-updating-recyclerview-items-using-payloads-1305f65f3068)
- [arXiv 2504.17999 — Streaming, Fast and Slow (Cognitive-Load-Aware)](https://arxiv.org/html/2504.17999v2)
- [Android Developers — Kotlin flows](https://developer.android.com/kotlin/flow)
- [lambiengcode/compose-chatgpt-kotlin-android-chatbot](https://github.com/lambiengcode/compose-chatgpt-kotlin-android-chatbot)
