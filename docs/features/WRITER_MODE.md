# Writer 模式 — 作家/小说创作

> Writer 模式的核心是「让模型在多轮对话里保持长篇连贯性」。所有 writer-only 逻辑收口到 `writer/` 子包 + `WriterModeStrategy`。

---

## 1. 触发条件

`MyAssistant.type == "writer"` → `SessionMode.WRITER` → `WriterModeStrategy`。

用户在「编辑助手」界面选「作家」类型（[EditMyAssistantActivity.kt](../../app/src/main/java/com/example/aichat/EditMyAssistantActivity.kt)）。

---

## 2. UI 表现

| 元素 | 行为 |
|---|---|
| Toolbar 右上 「📋 大纲」按钮 | 仅 writer 显示，跳转 [SessionOutlineActivity](../../app/src/main/java/com/example/aichat/SessionOutlineActivity.kt) |
| 消息长按 → "转大纲" action | 仅 writer 响应（[WriterModeStrategy.onOutlineAction](../../app/src/main/java/com/example/aichat/session/WriterModeStrategy.kt)） |
| 章节快速跳转（菜单选项） | 全模式可用，但实际使用率主要在 writer |
| 不显示自动 TTS 按钮 | `supportsAutoTts = false` |

---

## 3. 数据模型

### `SessionOutlineItem`（[源](../../app/src/main/java/com/example/aichat/SessionOutlineItem.kt)）

会话级大纲条目。一个 session 可有多条，类型分：
- `chapter` — 章节大纲
- `volume` — 卷大纲（含 `volumeChapters: List<String>` 表示该卷覆盖哪些章节标题）
- `character` — 人物资料
- `setting` — 世界观/设定
- `note` — 自由备注

字段 `selected: Boolean`（仅 volume 用）—— 表示这个卷大纲是否参与"知情屏蔽"（详见 §6）。

### 存储：[SessionOutlineStore.kt](../../app/src/main/java/com/example/aichat/SessionOutlineStore.kt)

SharedPreferences JSON，未走 Room 迁移。后续如果做 R11/R12 迁移要注意。

---

## 4. Writer 改写用户消息（buildUserMessageForApi）

每次发消息前，[WriterModeStrategy.buildUserMessageForApi](../../app/src/main/java/com/example/aichat/session/WriterModeStrategy.kt) 拼接大纲块到末尾：

```
用户输入
（原样）

【写作大纲与资料】
{outlineBlock}

请严格参考以上内容，保持情节、设定、任务线索的一致性与准确性。
```

`outlineBlock` 由 Activity 在 `buildSessionContext()` 调用 [`OutlinePromptBuilder.build(outlines, includeKnowledgeEnforcement = true)`](../../app/src/main/java/com/example/aichat/OutlinePromptBuilder.kt) 生成。

OutlinePromptBuilder 内部规则：
- selected=true 的卷大纲优先（其 chapterTitles 之外的 chapter 条目会被屏蔽）
- character / setting / note 全部包含
- 章节按 sortOrder 排
- 整块如果超 N 字会截断（具体阈值看源码）

---

## 5. Writer 改写历史消息（buildHistoryForApi）

长会话发 LLM 容易爆 context。WriterModeStrategy 在 [`buildHistoryForApi`](../../app/src/main/java/com/example/aichat/session/WriterModeStrategy.kt) 里：

- **最新一条助手消息**：取「前 1000 字 / 中段 1000 字 / 尾段 1000 字」拼起来（`buildLastAssistantExcerpt`）—— 保留关键语气和事实锚点
- **更早的助手消息**：取前 500 字 + 节选说明
- **user 消息**：原样不动
- **常量在 `SessionContext` 默认值里**（`writerLastSegmentChars=1000`, `writerEarlyExcerptMaxChars=500`）

CHARACTER / DEFAULT 模式不节选。

---

## 6. Writer 专属生成功能

### Session 大纲（一键总结整个会话）

`SessionOutlineActivity` 触发 `WriterOutlineService.generateSession(history, prompt, callback)`：
- 把整个会话历史压缩成 80-320 字纯文本大纲
- 用 dedicated 模型配置（[AiModelConfig](../../app/src/main/java/com/example/aichat/AiModelConfig.kt) 的"task 模型"通道）
- 走 streamChat 但 non-streaming response（json_object）
- 持久化为 `SessionOutlineItem(type="session")`

### 单条消息转大纲

`ChatSessionActivity.summarizeMessageToOutline(message)`（被 [WriterModeStrategy.onOutlineAction](../../app/src/main/java/com/example/aichat/session/WriterModeStrategy.kt) 间接调用）：
- `WriterOutlineService.summarize(content, prompt, callback)`
- 把一条助手回复压成 80-200 字大纲条目（含 chapterTitle）
- 持久化为 `SessionOutlineItem(type="chapter", title=自动起的标题)`

### 章节计划 JSON（chapter plan）

[`WriterChapterPlanService.generate(ctx, callback)`](../../app/src/main/java/com/example/aichat/writer/WriterChapterPlanService.kt) 是最复杂的：

- 输入：[`ChapterPlanContext`](../../app/src/main/java/com/example/aichat/ChapterPlanDraft.kt)（含 outline 历史、人物、设定、目标章节序号、风格指引）
- 输出 JSON 结构：
  ```json
  {
    "chapterGoal": "...",
    "startState": "...",
    "endState": "...",
    "characterDrives": [{"name":"小明","goal":"...","misbelief":"...","emotion":"..."}],
    "knowledgeBoundary": [...],
    "eventChain": [...],
    "foreshadow": [...],
    "payoff": [...],
    "forbidden": [...],
    "styleGuide": "...",
    "targetLength": "..."
  }
  ```
- 模型经常返回不规范的 JSON（trailing comma / smart quote / 截断）→ 走多层 fallback：
  1. 直接 JSON parse
  2. 提取首段 `{...}` 切片再 parse
  3. 检测截断 → `repairTruncatedJsonObject` 补 `]}`
  4. 关键字扫描兜底（`extractChapterPlanByKeywords` 用正则按字段名抓字符串/数组）
- 全部容错逻辑在 [WriterJsonHelpers.kt](../../app/src/main/java/com/example/aichat/writer/WriterJsonHelpers.kt)

### 卷大纲 / 知情约束

`WriterVolumeService`：
- `generateVolume(startChapter, endChapter, callback)` — 区间内章节总览
- `extractKnowledge(outlineText, callback)` — 从章纲文本抽出"哪些设定不能让本章泄露"列表

---

## 7. SessionOutlineActivity（[源](../../app/src/main/java/com/example/aichat/SessionOutlineActivity.kt)）

是一个独立 Activity，专门管理 session 大纲。功能：
- 大纲条目 CRUD（chapter / volume / character / setting / note 五种）
- 「更多」菜单：生成 session 大纲 / 生成章纲 JSON / 卷大纲生成 / 知情约束抽取
- 编辑某条大纲（dialog）
- 重新排序、删除、复制

这个 Activity 直接 `ChatService(this).generateChapterPlanJson(...)` 等调用，**不走 ViewModel**（因为是 dialog 一次性用，没有持久化状态）。

---

## 8. 章节跳转（writer 主要受益方）

[`ChapterJumpController`](../../app/src/main/java/com/example/aichat/session/ChapterJumpController.kt) 在长会话场景下让用户从对话框里跳到某条助手消息：

- 列表来源：所有 `role=ROLE_ASSISTANT` 且 content 非空的消息（按时间排）
- 每项预览：取助手回复的第一非空行，截断 40 字
- 跳转：定位到 RecyclerView 位置（自动展开 "历史区" 折叠卡片），smoothScroll 到 timestamp view 顶部
- 最多 12 次重试（60ms 间隔）等 RV 重绘
- 入口：toolbar 「⋮ 更多」菜单 → 「章节跳转」

---

## 9. 常见陷阱

1. **`outlineBlock` 每次发消息都重新构造** → outlineStore.getAll(sessionId) 每次发都查一次 SP。SP 读快，但如果大纲条目多（>50）有感知。考虑 R11 时迁 Room。
2. **章节计划 JSON 容错容易"假成功"** → keyword 扫描 fallback 可能漏字段。如果用户报告"章纲缺少 X"，先看 `countNonEmptyPlanFields` 日志（[WriterJsonHelpers](../../app/src/main/java/com/example/aichat/writer/WriterJsonHelpers.kt)）确认模型实际返回了几个字段。
3. **`includeKnowledgeEnforcement` 参数**：true 时 outlineBlock 末尾会拼"以下设定不要泄露给读者"段落（writer 防剧透）。改默认值前先确认所有 callsite。
4. **`SessionOutlineItem.selected` 只用于 volume**：UI 上仅 volume 行显示开关。如果给 chapter 加 selected 会被 store 忽略。
5. **session 大纲 / 章纲 都使用「任务模型」配置**（[AiModelConfig.getConfigForTask](../../app/src/main/java/com/example/aichat/AiModelConfig.kt)）—— 与主聊天模型分离。改 prompt 时不会影响主聊天，但要确认用户在「设置 → 模型」里配过任务模型。
6. **`buildLastAssistantExcerpt` 总长 ≤ 3*segment 时不节选**（即助手消息 ≤ 3000 字直接全发）。

---

## 10. 加新 writer 功能去哪改？

| 我想… | 改 |
|---|---|
| 改大纲注入到 prompt 的格式 | `WriterModeStrategy.buildUserMessageForApi` + `OutlinePromptBuilder.build` |
| 加新的大纲条目类型 | `SessionOutlineItem` + `SessionOutlineStore.normalizeType` + `SessionOutlineAdapter` |
| 改章纲 JSON 字段 | `WriterChapterPlanService.buildChapterPlanUserPrompt`（prompt）+ `WriterJsonHelpers.normalizeChapterPlanJson`（规范化）+ `extractChapterPlanByKeywords`（关键字 fallback）|
| 改历史消息节选阈值 | `SessionContext` 的 `writerLastSegmentChars` / `writerEarlyExcerptMaxChars` 默认值 |
| 加 volume / knowledge 抽取规则 | `WriterVolumeService` + 对应 prompt |
| 在 chat() 流里加 writer-only 行为 | **不要这么做** —— 走 `WriterModeStrategy` 钩子，否则破坏 SRP |
