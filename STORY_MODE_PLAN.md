# 故事模式重构计划（替代 inkos）

> 目标：用本地结构化 outline 取代 inkos 远程生成，把 inkos 的 5 段 SECTION（story_frame / volume_map / roles / book_rules / pending_hooks）拆成本地可见、可编辑、可注入 prompt 的多类型大纲条目；同时把 outline UI 从平铺列表改成分组折叠卡片，每个条目可单独展开编辑。

不新增 `SessionMode`，**继续走 writer**。inkos 完全删除。

---

## 0. 设计原则

1. **数据先行 UI 后做**：先把 `SessionOutlineItem` 扩 `metaJson` + 定义新 type 的 schema，prompt builder 同步改造，再做 UI。
2. **新 type 不靠 enum，靠 type 字段字符串**：现状 `type` 已经是 String，沿用即可（chapter / volume / world / task / material / knowledge → 增 status / relation / subplot / emotion / foreshadow_state）。
3. **structured data 走 metaJson（JSON 字符串）**：避免给 `SessionOutlineItem` 每种 type 都加专属字段。Adapter / OutlinePromptBuilder 按 type 解析。
4. **向后兼容**：旧 outline（无 metaJson）按现有逻辑展示与注入，不强迫迁移。
5. **UI 单层折叠 + 单项展开**：参考图上结构 —— Section 卡片可折叠（章节 / 角色 / 状态卡 / …），每个 section 内单条 item 可点击展开看详情/编辑。

---

## 1. 数据模型

### 1.1 `SessionOutlineItem` 加字段

```kotlin
data class SessionOutlineItem(
    var id: String = "",
    var type: String = "",
    var title: String = "",
    var content: String = "",
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var selected: Boolean = true,
    var volumeChapters: List<String> = emptyList(),
    // 新增：结构化数据，按 type 各自定义 schema，存 JSON 字符串
    var metaJson: String = "",
)
```

`SessionOutlineStore` 序列化层兼容（多字段不影响 Gson/JSON 反序列化老数据）。

### 1.2 type 重命名（type 字段语义化）

| 旧 type       | 新 type      | 显示名     | 备注                              |
| ------------- | ------------ | ---------- | --------------------------------- |
| `task`        | **`roles`**  | 角色       | task 是历史命名遗留，语义模糊     |
| `material`    | **`foreshadow`** | 伏笔   | 顺手改清楚，伏笔比"资料"准确      |
| `world`       | `world`      | 世界观     | 不动                              |
| `knowledge`   | `knowledge`  | 知情约束   | 不动                              |
| `chapter`     | `chapter`    | 章节       | 不动                              |
| `volume`      | `volume`     | 卷         | 不动                              |

迁移：`SessionOutlineStore` 读条目时遇到 `task` → 改写为 `roles`，遇到 `material` → 改写为 `foreshadow`，写回一次。一次性懒迁移，不走 Room migration。

### 1.3 新 type 列表与 metaJson schema

| type             | 显示名     | metaJson 字段                                                                                    | 是否新增 |
| ---------------- | ---------- | ------------------------------------------------------------------------------------------------ | -------- |
| `chapter`        | 章节       | （沿用 content）                                                                                 | 已有     |
| `volume`         | 卷         | （沿用 volumeChapters）                                                                          | 已有     |
| `world`          | 世界观     | （沿用 content）                                                                                 | 已有     |
| `roles`          | 角色档案   | `{tier:"major"|"minor"|"extra", tags:[], appearance, personality, background, motivation, arc}`  | 重命名+扩字段 |
| `foreshadow`     | 伏笔池     | `{state:"planted"|"developing"|"paid_off", plantedChapter?:String, paidOffChapter?:String, halfLife:"short"|"medium"|"long"|"endgame"}` | 重命名+扩字段 |
| `knowledge`      | 知情约束   | （沿用 content）                                                                                 | 已有     |
| **`status`**     | 状态卡     | `{ownerRoleId:String, current:String}` ← history **不本地存**，走 wi-server                       | **新增** |
| **`relation`**   | 角色关系   | `{fromRoleId, toRoleId, kind:"亲缘"|"敌对"|"暧昧"|...", level:Int(-100..100), note}`           | **新增** |
| **`subplot`**    | 支线       | `{progress:Int(0..100), milestones:[{chapter, desc, done:Bool}], linkedRoleIds:[]}`              | **新增** |
| **`emotion`**    | 感情线     | `{roleIdA, roleIdB, stage:"陌生"|"试探"|"靠近"|"亲密"|"破裂"|"和解"|"分别", progress:Int}`     | **新增** |
| **`rules`**      | 叙事规则   | 字段级 JSON：`{protagonist, tone, pov, tense, taboos:[], styleRefs:[], customYaml?}` ← 用户**按字段编辑**，app 拼回 YAML 发 prompt | **新增** |

> 角色身份引用：`roles` 类条目的 `id` 即 roleId，其他 type 引用时存 id 字符串；UI 展开时按 id 反查显示名（找不到时显示「(已删除)」），**不做级联删除**。

### 1.4 状态卡 history 落 wi-server

状态卡的 `history`（每章变化记录）只本地存 `current`，**历史落 wi-server**：

- 新增 server 端 endpoint（在 `wi-chat-server` 的 worktree 里加，与 `/api/tool/memory-recall` 同套服务）：
  - `POST /api/story/status-history` `{sessionId, statusCardId, chapter, change, ts}` — append
  - `GET /api/story/status-history?sessionId=&statusCardId=&limit=20` — 拉最近 N 条
- 本地 outline 编辑「状态卡」展开时按需拉历史；prompt 注入时**只取最近 3 条**
- 服务端用 SQLite 表 `story_status_history(session_id, card_id, chapter, change, ts)` 即可
- 离线 / 服务挂时优雅降级：UI 显示"历史暂不可用"，prompt 注入只写 current

> 与 `wi_chat_server_worktree` 那条 memory note 提到的"server worktree 未合主分支"风险一致：要么先把 worktree 合主，要么这条 endpoint 先做成 stub 等合主再上。

### 1.5 `SessionChatOptions` / `Entity` 清理

删除 6 个 inkos 字段：`inkosEnabled` / `inkosBookId` / `inkosSubtype` / `inkosBookRulesYaml` / `inkosTargetChapters` / `inkosChapterWordCount`。

`inkosBookRulesYaml` 的内容**迁移**：`SessionChatOptionsStore.read` 里做一次性懒迁移 —— 发现旧 entity 有非空 `inkosBookRulesYaml`，解析 YAML 抽出 protagonist / tone / pov / tense / taboos / styleRefs 字段（字面取，取不到的字段塞进 `customYaml` 整段保底），写一条 `type=rules` 的 SessionOutlineItem，然后清字段。Room 走 `fallbackToDestructiveMigration = false` + `MIGRATION_<n>_<n+1>` 删列（不允许丢数据）。

---

## 2. Prompt 注入（OutlinePromptBuilder 改造）

`OutlinePromptBuilder.build()` 增加新分组段落，顺序：

1. 叙事规则（rules）—— **最先**，包在 \`\`\`yaml 围栏里
2. 卷大纲（volume，selected）—— 现状
3. 章节大纲（chapter，去 volume 覆盖）—— 现状
4. **角色档案（task）** —— 改为按 tier 分组（主角 / 配角 / 配角-极简），逐条按 metaJson 字段展开（## 标签 / ## 性格 / ## 背景 / ## 动机 / ## 弧线）
5. **角色关系矩阵（relation）** —— 一句一条：`{fromName} → {toName}：{kind}（level={level}）{note}`
6. 世界背景（world）—— 现状
7. 知情约束（knowledge）+ 硬约束句 —— 现状
8. **支线进度（subplot）** —— `{title}（进度 {progress}%）` + milestones 完成/未完成清单
9. **感情线（emotion）** —— `{a} ↔ {b}：{stage}（{progress}%）`
10. **伏笔池（material 带 state）** —— 按 state 分子段：已埋下 / 铺垫中 / 已回收
11. **状态卡（status）** —— 按 ownerRole 分组，列出 current；history 只取最近 3 条
12. 其他资料（material 无 state）—— 现状

新增工具方法 `OutlinePromptBuilder.findRoleNameById(items, id)` 供关系/感情/状态展开用。

`buildFull()`（用于子任务 prompt）一并支持新 type。

---

## 3. AI 可调用的 Story Tools（参考 inkos agent-tools）

inkos 给子 agent 暴露了一套结构化 tool（`agent-tools.d.ts`），分两类：
- **通用文件操作**：Read / Edit / Write / Grep / Ls（按 bookId 作用于书的项目目录）
- **业务工具**：`createSubAgent` / `writeTruthFile` / `renameEntity` / `patchChapterText` / `generateCover` / `shortFictionRun`

我们不写盘也不分 agent，但同一思路用在「让模型直接维护 outline」很合适。在现有的 ChatToolCallAccumulator / 工具协议基础上加一组 **Story Tools**，注册成 OpenAI 兼容的 function tool，模型可以在 writer 模式下调用来实时更新 outline。

### 3.1 Tool 清单

| name                       | 参数                                                                          | 行为                                                                  |
| -------------------------- | ----------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| `list_outline`             | `{type?: string}`                                                             | 返回 outline 条目（按 type 过滤），id + title + 摘要                  |
| `read_outline_item`        | `{id: string}`                                                                | 返回单条完整内容 + metaJson                                           |
| `add_outline_item`         | `{type, title?, content?, metaJson?}`                                         | 新建条目，返回 id                                                     |
| `update_outline_item`      | `{id, title?, content?, metaJson?}`                                           | 局部 patch（未传字段保持）                                            |
| `delete_outline_item`      | `{id, reason?}`                                                               | 删除                                                                  |
| `rename_role`              | `{oldName, newName}`                                                          | 改 roles 条目 title + 全 outline 文本里的字面替换（仿 inkos renameEntity） |
| `append_status_history`    | `{statusCardId, chapter, change}`                                             | 走 wi-server 写一条历史，同步更新本地 status.current                  |
| `update_subplot_progress`  | `{id, progress, addMilestone?: {chapter, desc, done}}`                        | 推进支线 + 可选追加 milestone                                         |
| `bump_foreshadow`          | `{id, state, chapter?}`                                                       | planted → developing → paid_off                                       |
| `update_emotion_stage`     | `{id, stage?, progress?}`                                                     | 推进感情线                                                            |
| `patch_chapter_text`       | `{chapterId, targetText, replacementText}`                                    | 章节内文本精准替换（仿 inkos patchChapterText）                       |

### 3.2 实现要点

- 工具定义放在新建 `app/.../story/StoryTools.kt`，按 OpenAI tool schema 暴露
- 在 `ChatGenerator` / `streamChat` 里：若当前 session 是 writer 模式，把这套 tools 拼到 request body 的 `tools` 数组
- tool call 落地：复用 `ChatToolCallAccumulator` 流式拼装 → 在客户端本地执行（调 `SessionOutlineStore` / wi-server）→ 把结果以 `role=tool` 消息回写给模型
- 与现有 `search_memory` tool 共用同一注册机制；新增 `provider 白名单`（参考 [project_tool_provider_compat](.claude/projects/-Users-evannawu-private-space-chatbox-android/memory/project_tool_provider_compat.md)）—— OpenAI / Anthropic / Claude 支持，llama.cpp / Ollama / Gemini 关闭这组 tool 避免 400
- 工具调用必须**幂等可重试**：每个 add/update 在客户端先 dry-run 校验 metaJson schema，失败时返回 error 让模型自己修
- UI 反馈：模型调 tool 时 outline 页面如果在前台，监听 `SessionOutlineStore` 变化自动刷新（已有 listener 机制可复用）

### 3.3 prompt 引导

system prompt 里加一段 story-tools 使用指南（仅 writer 模式注入），列举什么场景该调哪个 tool（写完一章后 `bump_foreshadow` / `append_status_history` / `update_subplot_progress`；用户改名时 `rename_role`；新发现人物 `add_outline_item type=roles`）。

---

## 4. UI 改造

### 4.1 列表骨架：分组卡片 + 单项展开

`SessionOutlineAdapter` 由「平铺 N 条」改成「Section 包 Sub-Item」两级结构：

```
[Section: 章节]          (卡片1，可整体折叠)
  - chapter 01 ...        (一行显示 title + content 字数)
  - chapter 02 ...        (点击展开 → 显示 content + 操作按钮)
  ...
[Section: 角色]          (卡片2)
  - 角色 A   [主角 tag]   (点击展开 → 显示 metaJson 各字段 + 编辑)
  - 角色 B   [配角 tag]
  ...
[Section: 角色关系]      (卡片3)
[Section: 状态卡]
[Section: 伏笔池]
[Section: 支线 / 感情线]
[Section: 世界观 / 知情约束 / 资料]
[Section: 叙事规则]      (一条 rules，直接显示 YAML preview)
```

实现方式（选其一）：

- **方案 A**：保留 `SessionOutlineAdapter`，改成多 viewType（header / item / expanded-item / footer），手动管理 `expandedIds: Set<String>` 与 `collapsedSections: Set<String>`。改动小，状态在 adapter 里。
- **方案 B**：用 `ConcatAdapter`，每个 Section 一个子 adapter。新增 `OutlineSectionAdapter`（一个卡片 + 一个 RecyclerView）。结构更清晰，但要处理嵌套滚动。

推荐 **方案 A**（已有项目都是单 RecyclerView 多 viewType 的风格，没必要引入嵌套 RV）。

### 4.2 新 layout

- `item_outline_section_header.xml`：卡片顶部带分组名 + 折叠箭头
- `item_outline_role.xml`：角色行（图标 + 名字 + tier tag + chevron）
- `item_outline_role_expanded.xml`：展开后 metaJson 字段编辑表单
- `item_outline_status.xml` / `item_outline_relation.xml` / `item_outline_subplot.xml` / `item_outline_emotion.xml`：各类型专属行（折叠态）
- `item_outline_rules.xml`：YAML 预览 + 「编辑规则」按钮

颜色 / drawable 沿用 CLAUDE.md 已定义的 ios_cell_bg / ios_separator / ios_section_label / glass_*。Section 卡片用 MaterialCardView + `bg_glass_toolbar` 头部 + 内部分隔线。

### 4.3 编辑页

- 角色编辑：从「title + content 双 EditText」升级成多字段表单（tier 选择 + 标签 chips + 5 段 textarea），对齐 inkos 角色卡的小标题：核心标签 / 反差细节 / 人物小传 / 当前现状 / 关系网络 / 内在驱动 / 成长弧光
- 关系 / 支线 / 感情线 / 状态卡：每种一个 BottomSheet 或 dialog，按 metaJson schema 渲染表单
- **叙事规则按字段编辑（不让用户碰 YAML 原文）**：protagonist / tone（基调）/ pov（视角）/ tense（时态）/ taboos（禁忌项 chips）/ styleRefs（风格参考 chips）逐字段编辑；保存时由 app 把字段拼回 YAML 字符串，prompt 注入直接用拼好的 YAML。`customYaml` 字段是逃生口（老用户从 inkosBookRulesYaml 迁移过来的非结构化内容存这里），单独一个折叠区给"高级"用户编辑，不在主表单显示

### 4.4 popup menu：章节计划 → 生成书籍

`SessionOutlineActivity.showMoreMenu` 当前菜单 `["知情注入", "章节计划", "生成卷纲"]`：

- 「章节计划」**注释掉**（保留代码，加 `// TODO(story): 评估是否回归`），菜单 label 改成 **「生成书籍」**，同位置同顺序，避免肌肉记忆乱
- 「生成书籍」点击 → 新方法 `runBookGeneration()`：
  1. 检查 session 是否已有 chapter 类条目（≥1），否则 Toast 提示「请先添加至少一章章节大纲」
  2. 拼一个特殊 prompt 发给模型：把所有 chapter 内容 + 现有 world / roles / volume 作为输入，让模型按结构化输出：world 段（自由文本）/ roles 数组（每个含 metaJson 全字段）/ volume_map 数组（含 volumeChapters 覆盖）/ rules 字段
  3. 模型走流式返回，**直接调用 §3 的 Story Tools**（`add_outline_item type=world/roles/volume`、`update_outline_item type=rules`），不需要客户端再解析输出格式
  4. 进度反馈用现有的"后台生成"controller（ChapterPlanGeneration 那套），把 status 改成「正在生成书籍设定…」
  5. 完成后弹一个总结对话框，列出本次生成/更新的条目数：「新建 5 个角色 / 3 个卷 / 1 条叙事规则」，允许用户撤销

> 这个功能本质是「用模型 + Story Tools 反向初始化 outline」，跟 inkos 建书是同一个目标，但完全在本地走 chat 协议 + tool 调用，不依赖远程服务。

### 4.5 SessionOutlineActivity 清理

- 删 `sendOutlineToInkos` / `watchInkosBookProgress` / `inkosListeners`
- 删「Ink toggle 打开时跳 inkos」分支
- 顶部 toolbar 加「叙事规则」按钮（如果当前 session 没 rules item 显示「新建」，有就显示「编辑」）

---

## 5. inkos 删除清单

完全删除：

```
app/src/main/java/com/example/aichat/inkos/         (整包 4 文件 760 行)
app/src/main/java/com/example/aichat/BookInfoActivity.kt
res/layout/activity_book_info.xml                   (如果有)
```

修改去掉 inkos 引用：

- `SessionChatOptions.kt` / `SessionChatOptionsEntity.kt` / `SessionChatOptionsStore.kt`：去 6 个字段
- `SessionChatSettingsActivity.kt`：去 ink 设置区（subtype / yaml / targetChapters / chapterWordCount）+ 引入
- `SessionOutlineActivity.kt`：去 inkos 分支 + watch listener
- `AndroidManifest.xml`：去 `BookInfoActivity` 注册
- `AIChatApp.kt`：去 inkos init（如果有）
- `ChatGenerator.kt` / `ChatViewModel.kt` / `ChatSessionActivity.kt`：去 inkos 引用
- `docs/AI_GUIDE.md` / `docs/README.md` / `docs/features/SESSION_MODES.md` / `docs/features/CHAT_FLOW.md` / `docs/features/WRITER_MODE.md`：去 inkos 章节，改写为「故事模式 = writer + 扩展 outline」

`SessionModeStrategy.kt` 注释里去掉「以 inkos 为例」的描述。

---

## 6. 实施分期

| 阶段 | 内容 | 风险 / 备注 |
|---|---|---|
| **S1：数据模型 + 兼容** | `SessionOutlineItem.metaJson` 字段；type 重命名 `task→roles` / `material→foreshadow`（懒迁移）；各 type 的 JSON schema（Kotlin data class + Gson）；旧数据反序列化兼容 | 低 |
| **S2：inkos 删除** | 删整包 + 6 字段 + BookInfoActivity + 设置页 ink 区 + outline activity inkos 分支；inkosBookRulesYaml → rules item 迁移 | 中 — 字段删除走 Room migration |
| **S3：OutlinePromptBuilder 扩展** | 加新 type 的 prompt 段落 + role 名字反查；老 prompt 输出不变 | 低 |
| **S4：UI section + 折叠** | Adapter 改多 viewType；新 layout（section header + 各类型 item + expanded form）；section 折叠状态持久化（用 SP 即可） | 高 — 改动最大 |
| **S5：单项展开 + 类型化编辑** | 角色 / 关系 / 状态 / 支线 / 感情 / 伏笔 / 规则 各自的展开视图 + 编辑表单（**规则按字段编辑后拼回 YAML**） | 高 |
| **S6：Story Tools + 生成书籍** | `StoryTools.kt` 注册 + tool 调用执行落 outline；popup menu「章节计划→生成书籍」；wi-server 加 status-history endpoint | 高 — 涉及服务端改动 |
| **S7：文档同步** | CLAUDE.md Phase 进度更新 + WRITER_MODE.md + SESSION_MODES.md + 新建 STORY_TYPES.md（描述 metaJson schema 与 Story Tools） | 低 |

S1 + S3 可并行；S2 独立；S4 必须在 S5 之前；S6 依赖 S1/S3 完成；S7 收尾。

---

## 7. 验证

- 旧 writer session 打开 outline 页：所有老 type 自动迁移 `task→roles` / `material→foreshadow`，显示在新 section 卡片里，无内容丢失
- 旧 session 如果有 `inkosBookRulesYaml`，迁移成功后能在「叙事规则」section 看到字段化的 protagonist/tone/pov/... + customYaml 保留区
- 新建角色（roles）能用结构化表单编辑，prompt 注入按 ## 小标题分段，跟 inkos 时代手感接近
- 新建状态卡 / 关系 / 支线 / 感情线，发对话后看 prompt 里是否注入对应段落（debug log 打 final user message）
- 「生成书籍」：用一个只有 3 章空标题大纲的 session 跑一遍，验证模型能用 Story Tools 把 world / roles / volume / rules 填齐
- 状态卡 history 写入 wi-server 后，离线状态下 outline 编辑器能优雅降级（不崩溃，只显示 current）
- inkos 全部入口（设置页 ink 区 / 查看书籍信息 / outline ink toggle）消失，无残留崩溃

---

## 8. 已决定（之前的开放问题）

1. **roleId 引用稳定性** → 不做级联，前端显示「(已删除)」
2. **角色矩阵 UI** → 列表展示（仿 inkos）；可视化矩阵进后续迭代
3. **状态卡历史** → 本地只存 `current`，history 落 **wi-server**（见 §1.4）；prompt 注入只取最近 3 条
4. **叙事规则编辑** → 用户**按字段编辑**（protagonist/tone/pov/tense/taboos/styleRefs），app 拼回 YAML 发 prompt；不让用户碰 YAML 原文，避免语法错误；老 inkos YAML 迁移留 `customYaml` 逃生口
