# 2026-06-06 夜间任务交接

夜间自动跑的两个任务结果汇总，明天醒了看这一份即可。

---

## 任务 1：章节跳转 bug — **已修复**

### 改动
- `app/src/main/java/com/example/aichat/session/ChapterJumpController.kt` — 重写 `jumpTo` / `attemptJump` / `scrollToMessageInRecycler` / `computeScrollYInContainer`

### 修复的根因（3 个，按影响排序）

1. **同步 expand history 后立即 attemptJump**
   `setHistoryExpanded(true)` 只翻 visibility，layout pass 还没跑就去查子 view 的 `top` —— 全是 0/stale，跳到错位置。
2. **`scrollToPositionWithOffset` 主动有害**
   两个 RV 都是 `wrap_content` + `nestedScrollingEnabled=false`，跑在 NestedScrollView 里 —— 自身没滚动区。这时 `scrollToPositionWithOffset(pos, 0)` 会触发 LinearLayoutManager 一次错位 layout，让 `itemView.top` 不再代表 item 在 NSV 坐标空间里的真实 Y。
3. **`computeScrollYInContainer` 用 `view.top` 累加**
   只有 layout 完全跑完才可信；若有任意祖先 / 兄弟节点有 pending `requestLayout`（比如某条消息正在收 / 展），路径上 top 就乱了。

### 新逻辑

- expand 后挂 `OneShotPreDrawListener` 等下一帧再 attemptJump（兜底所有 pending layout）
- 用 `View.getLocationInWindow` 算 Y —— 反映**当前帧实际绘制位置**，不依赖各级 layout 干净
- 不再调 `scrollToPositionWithOffset`；item 没 bind 才退而 `scrollToPosition` 并等下一次 retry
- 加 `width==0 || height==0` 防御 —— bind 了但 measure 没跑完，让 retry 兜
- attemptJump 加 `isFinishing || isDestroyed` guard

### 验证
- `./gradlew :app:compileDebugKotlin` ✅
- 跑端到端测试待人工：明早建议测以下场景
  1. cardHistory 折叠状态打开章节列表 → 选历史区某条 → 应展开并精确跳过去
  2. 长会话先收起某条 assistant 消息 → 打开章节列表跳到下方某条 → 不应跑偏
  3. 在 currentAdapter 区（已展开） → 章节跳转上下移动 → 不应有抖动
  4. 边界：消息列表为空 / 只有 1 条助手消息

### 还未触碰的事
- `MAX_JUMP_RETRIES = 12` × `JUMP_RETRY_DELAY_MS = 60L` = 720ms 上限。修完根因后大概率 1 次就成功，retry 主要做兜底。如果生产环境还见到 toast `chapter_jump_failed`，回来看 retry 日志。
- 是否要在章节列表里**显示 history vs current 区段**：当前所有 chapter 一锅端，用户看不出来哪条在历史区。这是 UX 问题不是 bug，明天再决定要不要做。

---

## 任务 2：StreamTypewriter 调研 + 重构方案

### 现状（先讲我看到的"感觉不对"）

读了 `app/src/main/java/com/example/aichat/session/StreamTypewriter.kt` 和它在 Activity 里的调用点，4 个具体痛点：

1. **target 二次跟踪**：`activeStreamingMessage` setter 会同步 `setTarget(value)`；但 `enqueueDelta` 又会检查 `streamingTargetMessage !== message` 然后**清掉 pending** —— 等价于偷偷换 target。两条同步路径覆盖同一变量、语义不一致。
2. **`pendingChars.setLength(0)` 在 target 切换时直接丢字符** —— 在切流 / 旧消息 cancel 没干净的边缘 case 容易丢末尾。
3. **每帧两个 adapter 都 query**：`historyAdapter.renderStreamingMessageIfVisible(targetMsg) | currentAdapter.renderStreamingMessageIfVisible(targetMsg)`。流式消息几乎总在 current adapter，history 那次 query 是浪费 + bind 检查开销。
4. **fallback 到 `applyMessagesFully` 太重**：找不到 visible holder 就**整列重绘**。流式中如果用户上滚出视口，每个 tick 都 trigger 全表 setMessages —— 卡顿明显。

5. **节奏不自适应**：`CHARS_PER_FRAME = 4`、`FRAME_MS = 16` 是常量。后端突发推一段 200 字 → pendingChars 堆到 80 触发 throttle 升到 48ms（更慢） → 越积越多 → 用户看打字机像间歇性卡。

### 网上调研报告

📄 **报告路径**：[docs/typewriter-research.md](typewriter-research.md)

**推荐方案：D + B + C 组合**
- **方案 D（架构）**：把 streaming 状态从 Activity 搬到 ViewModel `StateFlow<StreamingState?>`。
  消灭 target 双轨 / cancel 4 出口 / model 被 mutate 这三个根因。
- **方案 B（节奏）**：自适应速率算法 —— 用最近 10 个 token 的移动平均到达间隔反推 per-char delay。
  解决 thinking 模式爆发吐 chunk 时打字机"追不上 → 排队 → throttle 变更慢"的死锁。
- **方案 C（VSync）**：用 `Choreographer.postFrameCallback` 替代 `Handler.postDelayed(16ms)`。
  字符出现节奏与屏幕刷新对齐，肉眼平滑度显著提升，几乎零额外代价。

报告里另外列出但**未推荐**的方案：
- 方案 A（GetStream Compose 实现）— 是 Compose only，我们是 View 系统
- 方案 E（100ms flush，不做打字机）— 反方向，放弃打字感本身

**迁移路径（10 步 R10.1-R10.10，预计 1.5d 编码 + 0.5d 回归）**：
见报告第 6 节"迁移 outline"。关键里程碑：
1. R10.1-R10.3：抽 `StreamingState` 数据类 → 移到 ViewModel 的 `StateFlow` → ChatService.onDelta 走 ViewModel
2. R10.4-R10.5：`TypewriterClock`（自适应速率，纯逻辑可单测）+ `TypewriterEngine`（Choreographer + coroutine）
3. R10.6-R10.7：Activity 改成 collect；MessageAdapter 加 payload 感知，**杀掉 `applyMessagesFully` fallback**
4. R10.8-R10.10：cancel/finish 双出口；删 `StreamTypewriter.kt`；加单测

**风险点**（报告第 6 节末有详细描述）：
- Choreographer + coroutine 桥需要写个 `awaitFrame()` suspend；Compose 已有 `withFrameNanos` 但我们不引 Compose runtime，自己 wrap 即可（10 行）
- adapter payload 改造影响 ToolCallMessageBinder 等其它 caller，要分类型处理 payload

### 明日待 Evanna 拍板的问题

下面这几个是**需要你判断**的设计选择，我没有自动改：

1. **协程 vs Handler**：当前是 Handler。报告里推荐的方案如果是 Kotlin Flow + collectLatest，意味着把帧循环挂到 lifecycleScope，cancel/drain 用结构化并发。比 Handler 干净但需要小范围 churn 你的现有 ChatService → Activity 流式回调 path。**默认建议：保留 Handler，只内化字符消费节奏算法**，churn 最小。等你拍板。
2. **CHARS_PER_FRAME 改成自适应**：固定 4 字符 / 帧太僵硬。推荐改成
   `take = min(pending.length, max(2, pending.length / target_drain_frames))`，
   即根据 pending 队列长度推算每帧应该吃多少才能在 ~400ms 内 drain 完。这能消除"卡—快—卡"的间歇感。**默认我会推这个改动，但等你看完调研报告再确认**。
3. **`renderStreamingMessageIfVisible` 跨 adapter 重复 query**：建议改成"先记住 streamingTarget 属于哪个 adapter，只 query 那一个"。**这个改动很小，我倾向直接做**，但留给你最后说"行"。
4. **fallback applyMessagesFully 改成 no-op + 标 dirty**：消息滚出视口时不重绘，等下次 bind 再读最新 content。需要少量改动 adapter。**这个属于"修 ChatService.kt 体量已接近 2000 行"那张牌的延伸**，明天问你愿不愿意一起做。

### 建议明天的执行顺序
1. 你看 `docs/typewriter-research.md`，对推荐方案点头/换一个
2. 按上面 4 个问题挨个回答
3. 我开 R10 重构 PR

---

---

## 任务 3：Prompt 统一管理 — **已完成**

### 改动
新增 `app/src/main/java/com/example/aichat/prompts/Prompts.kt`（单一登记处），把散在 5 个文件的 10 个 prompt 全部收口。

**结构（总/类型分类 - prompt）**：
```
Prompts
├── Proactive          自动对话协议 V5 (含 closeness 调制)
│   ├── systemSuffix(closeness)
│   └── followUpInstruction(...)
├── Title              会话短标题命名
│   └── userPrompt(source)
└── Writer             小说写作子系统
    ├── DialogueOutline.system(styleGuide)
    ├── NovelSummary.system(styleGuide)
    ├── ChapterPlan.SYSTEM
    ├── VolumeMerge.SYSTEM
    └── KnowledgeBoundary.SYSTEM
```

### 迁移影响（callsites 全部更新）

| 文件 | 原 inline prompt | 新调用 |
|---|---|---|
| `ChatViewModel.kt` | `ProactivePromptBuilder.buildSystemSuffix(...)` | `Prompts.Proactive.systemSuffix(...)` |
| `proactive/ProactiveFollowUpWorker.kt` | 同上 + `buildFollowUpInstruction(...)` | `Prompts.Proactive.followUpInstruction(...)` |
| `chat/ChatTitleGenerator.kt` | inline "你是标题助手..." | `Prompts.Title.userPrompt(source)` |
| `writer/WriterOutlineService.kt` | 2 段 inline | `Prompts.Writer.DialogueOutline.system(...)` / `Prompts.Writer.NovelSummary.system(...)` |
| `writer/WriterChapterPlanService.kt` | inline 章节规划 prompt | `Prompts.Writer.ChapterPlan.SYSTEM` |
| `writer/WriterVolumeService.kt` | 2 段 inline | `Prompts.Writer.VolumeMerge.SYSTEM` / `Prompts.Writer.KnowledgeBoundary.SYSTEM` |
| `AppDatabase.kt` 注释引用 | `chat/ProactivePromptBuilder` | `prompts/Prompts.Proactive` |

`chat/ProactivePromptBuilder.kt` 已删除（功能完全搬到 `Prompts.Proactive`，零 callsite 残留）。

### 验证
- `./gradlew :app:compileDebugKotlin` ✅
- grep 双查：`你是` / `||==FOLLOWUP==||` 等 prompt 特征串现在只出现在 `Prompts.kt`（其它命中都是 JSON 字段名 / 协议解析器，不是 prompt）
- 字符串字节级未变 —— 全部走 copy-paste，没顺手"优化"措辞

### 后续可选清理（**没自动做，等你拍板**）
- `WriterChapterPlanService.buildChapterPlanUserPrompt(ctx)` — 构造 user 角色的结构化数据上下文（章节序列 / 人物 / 知情等），代码 ~50 行。这部分是**数据拼装**不是 prompt 文本，**没搬**进 Prompts.kt。如果你认为它也算"prompt"想统一管，明早告我，我把它也搬进去。我的判断：拼数据应该和 Service 在一起，prompt 是 LLM 角色文本，两者是不同关注点 → 保持现状更干净。

---

## 任务列表状态
- ✅ 章节跳转 bug 定位 + 修复 + 编译通过
- ✅ StreamTypewriter 调研（推荐 D+B+C 组合，迁移 outline 已就位）
- ✅ Prompt 统一管理 — 全部 10 个 prompt 已迁到 `prompts/Prompts.kt`

## 不做的事（夜间任务保守原则）
- 没碰 StreamTypewriter.kt 任何代码 —— 等你看完调研报告
- 没动 `activeStreamingMessage` setter —— 重构是它和 typewriter 一起改
- 没 commit / push —— 等你 review 全部改动后再 squash

## 改动文件清单（明早 `git status` 就能看到）
**新增**：
- `app/src/main/java/com/example/aichat/prompts/Prompts.kt`
- `docs/typewriter-research.md`
- `docs/night-task-handoff-2026-06-06.md`（本文件）

**修改**：
- `app/src/main/java/com/example/aichat/session/ChapterJumpController.kt` — 章节跳转 bug 修复
- `app/src/main/java/com/example/aichat/ChatViewModel.kt` — Prompts 切换
- `app/src/main/java/com/example/aichat/proactive/ProactiveFollowUpWorker.kt` — Prompts 切换
- `app/src/main/java/com/example/aichat/chat/ChatTitleGenerator.kt` — Prompts 切换
- `app/src/main/java/com/example/aichat/writer/WriterOutlineService.kt` — Prompts 切换 + import
- `app/src/main/java/com/example/aichat/writer/WriterChapterPlanService.kt` — Prompts 切换 + import
- `app/src/main/java/com/example/aichat/writer/WriterVolumeService.kt` — Prompts 切换 + import
- `app/src/main/java/com/example/aichat/AppDatabase.kt` — 注释里改个引用

**删除**：
- `app/src/main/java/com/example/aichat/chat/ProactivePromptBuilder.kt`
