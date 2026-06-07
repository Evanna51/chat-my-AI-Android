# 故事模式 — outline type 与 Story Tools

> Writer 模式下,outline 不只是"章节+卷纲",还承载结构化的角色 / 关系 / 状态 / 支线 / 感情线 / 伏笔 / 叙事规则等数据。本文档说明每种 type 的 metaJson schema、prompt 注入顺序、可被 AI 调用的 Story Tools。
>
> 替代历史的 inkos 接入(已删除,见 [STORY_MODE_PLAN.md](../../STORY_MODE_PLAN.md))。

---

## 1. outline type 总表

11 种 type, 集中在 [story/StoryTypes.kt](../../app/src/main/java/com/example/aichat/story/StoryTypes.kt):

| type         | 显示名     | metaJson schema (data class)  | UI 折叠后显示                          |
| ------------ | ---------- | ----------------------------- | -------------------------------------- |
| `chapter`    | 章节       | (无)                          | 标题 + 字数                            |
| `volume`     | 卷         | (无, `volumeChapters` 字段)   | 标题 + 覆盖几章                        |
| `roles`      | 角色       | [`RoleMeta`](#rolemeta)       | 名字 + tier badge + tags               |
| `relation`   | 角色关系   | [`RelationMeta`](#relationmeta) | from→to + kind + level                |
| `status`     | 状态卡     | [`StatusMeta`](#statusmeta)   | 状态名 + ownerRole 显示名              |
| `subplot`    | 支线       | [`SubplotMeta`](#subplotmeta) | 名字 + progress%                       |
| `emotion`    | 感情线     | [`EmotionMeta`](#emotionmeta) | A↔B + stage                            |
| `foreshadow` | 伏笔       | [`ForeshadowMeta`](#foreshadowmeta) | 标题 + state badge                |
| `world`      | 世界观     | (无)                          | 内容截断                               |
| `knowledge`  | 知情约束   | (无)                          | 标题 + 内容                            |
| `rules`      | 叙事规则   | [`RulesMeta`](#rulesmeta)     | "叙事规则"固定标题                     |

老 type `task` / `material` 已在 [SessionOutlineStore.getAll](../../app/src/main/java/com/example/aichat/SessionOutlineStore.kt) 自动迁移为 `roles` / `foreshadow`。

---

## 2. metaJson schema

定义在 [story/StoryMeta.kt](../../app/src/main/java/com/example/aichat/story/StoryMeta.kt),用 Gson 读写。空字符串视为空对象。

### RoleMeta
```kotlin
data class RoleMeta(
    val tier: String = "minor",            // major / minor / extra
    val tags: List<String> = emptyList(),
    val appearance: String = "",           // 反差细节 / 外观
    val personality: String = "",          // 核心标签 / 性格
    val background: String = "",           // 人物小传
    val motivation: String = "",           // 内在驱动
    val arc: String = ""                   // 成长弧光
)
```

### RelationMeta
```kotlin
data class RelationMeta(
    val fromRoleId: String,
    val toRoleId: String,
    val kind: String,            // 亲缘 / 敌对 / 暧昧 / 师徒 …
    val level: Int,              // -100..100
    val note: String,
)
```

### StatusMeta
```kotlin
data class StatusMeta(
    val ownerRoleId: String,
    val current: String,
    // history 不本地存, 走 wi-server (S6 后置 — 当前 stub)
)
```

### SubplotMeta
```kotlin
data class SubplotMeta(
    val progress: Int = 0,             // 0..100
    val milestones: List<SubplotMilestone> = emptyList(),
    val linkedRoleIds: List<String> = emptyList(),
)
data class SubplotMilestone(val chapter: String, val desc: String, val done: Boolean)
```

### EmotionMeta
```kotlin
data class EmotionMeta(
    val roleIdA: String, val roleIdB: String,
    val stage: String,        // 陌生 / 试探 / 靠近 / 亲密 / 破裂 / 和解 / 分别
    val progress: Int,
)
```

### ForeshadowMeta
```kotlin
data class ForeshadowMeta(
    val state: String = "planted",            // planted / developing / paid_off
    val plantedChapter: String = "",
    val paidOffChapter: String = "",
    val halfLife: String = "medium",          // short / medium / long / endgame
)
```

### RulesMeta
```kotlin
data class RulesMeta(
    val protagonist: String,
    val tone: String,
    val pov: String,           // first / third-limited / third-omniscient
    val tense: String,         // past / present
    val taboos: List<String>,
    val styleRefs: List<String>,
    val customYaml: String,    // 老 inkos book_rules 迁移逃生口
)
```

`StoryMeta.buildRulesYaml(m)` 把字段拼回 YAML, 注入 prompt 时直接用; `parseLegacyYaml(yaml)` 把老 inkos YAML 拆字段。

---

## 3. Prompt 注入顺序

由 [OutlinePromptBuilder.build](../../app/src/main/java/com/example/aichat/OutlinePromptBuilder.kt) 固定:

1. **rules** — YAML 围栏, 字面给模型遵守
2. **volume** (selected) — 卷大纲覆盖区段章纲
3. **chapter** (selected, 去 volume 覆盖)
4. **roles** — 按 tier (major/minor/extra) 分组, `## 小标题` 列出 metaJson 各字段
5. **relation** — `from → to: kind (强度 N) — 备注`
6. **world**
7. **knowledge** + 知情边界硬约束句
8. **subplot** — 名字 (进度 N%) + linkedRoles + milestones (✓ / ·)
9. **emotion** — `A ↔ B: stage (N%)`
10. **foreshadow** — 按 state 分子段 (已埋下 / 铺垫中 / 已回收)
11. **status** — 按 ownerRole 分组, 只发 current

roleId 反查找不到 → 显示 `(已删除)`, **不做级联删除**。

---

## 4. UI 折叠卡片

[SessionOutlineAdapter](../../app/src/main/java/com/example/aichat/SessionOutlineAdapter.kt) 是单 RecyclerView 多 viewType:

- `VT_HEADER`: 卡片头部 (标题 + 数量 + 折叠 chevron)
- `VT_ITEM`: 折叠态行 (图标 + 标题 + 副标/badge + chevron)
- `VT_EXPANDED`: 单项展开 (字段表 + 编辑/删除按钮)
- `VT_FOOTER`: 卡片底部圆角收口

折叠状态走 [SectionCollapseStore](../../app/src/main/java/com/example/aichat/SectionCollapseStore.kt) (SharedPreferences) 按 sessionId 持久化。

Section 顺序对齐 prompt 注入顺序, 但 `chapter` / `roles` / `rules` 即使无数据也保留头部 (引导用户去新建)。

---

## 5. 类型化编辑器

[story/StoryEditDialogs.kt](../../app/src/main/java/com/example/aichat/story/StoryEditDialogs.kt) 为每种 type 渲染表单:

| type        | 字段                                                                     |
| ----------- | ------------------------------------------------------------------------ |
| roles       | 名字 / tier picker / tags / 性格 / 外观 / 小传 / 内在驱动 / 成长弧光        |
| relation    | 关系名 / from picker / to picker / kind / level (-100..100) / 备注          |
| status      | 状态名 / ownerRole picker / current                                       |
| subplot     | 名字 / progress / linkedRoles / 说明 (milestones 保留 metaJson 原值)        |
| emotion     | A picker / B picker / stage picker / progress / 备注                       |
| foreshadow  | 标题 / state picker / 埋下章节 / 回收章节 / halfLife picker / 描述           |
| **rules**   | protagonist / tone / pov picker / tense picker / taboos / styleRefs<br>+ customYaml (老 inkos 迁移保底, 不主动编辑) |

**叙事规则按字段编辑**, app 拼回 YAML 注入 prompt → 用户不会写坏 YAML 语法。

老 type (`chapter` / `volume` / `world` / `knowledge`) 走旧 `dialog_edit_outline` (title + content 双 EditText)。

---

## 6. Story Tools (AI 可调用)

[story/StoryToolSchemas.kt](../../app/src/main/java/com/example/aichat/story/StoryToolSchemas.kt) 暴露 OpenAI 兼容 tool;
[story/StoryToolHandler.kt](../../app/src/main/java/com/example/aichat/story/StoryToolHandler.kt) 本地执行 (操作 SessionOutlineStore)。

通过 [ToolBridge.build](../../app/src/main/java/com/example/aichat/sync/ToolBridge.kt) 检测 `assistant.type == "writer"` 自动启用 — 非 writer session 不感知 Story Tools。

| tool                       | 作用                                                                 |
| -------------------------- | -------------------------------------------------------------------- |
| `list_outline`             | 列出全部或按 type 过滤的条目 (id + title + 摘要)                     |
| `read_outline_item`        | 读单条完整内容 + metaJson                                            |
| `add_outline_item`         | 新建; metaJson 必须匹配 type schema                                  |
| `update_outline_item`      | 局部 patch (metaJson 走 merge)                                       |
| `delete_outline_item`      | 删除                                                                 |
| `rename_role`              | 改 roles.title + 全 outline 字面替换 (仿 inkos `renameEntity`)       |
| `bump_foreshadow`          | planted → developing → paid_off                                      |
| `update_subplot_progress`  | 推进 + 可选追加 milestone                                            |
| `update_emotion_stage`     | 推进感情线                                                           |
| `append_status_history`    | 追加状态变化 (wi-server 待加 endpoint, 当前本地仅更 current)         |
| `patch_chapter_text`       | 章节内文本精准替换 (仿 inkos `patchChapterText`, 要求 unique substring) |

失败统一返回 `{ok:false, error, message}` 让 LLM 自修复, **绝不抛异常**。

---

## 7. 「生成书籍」一键流程

[story/BookGenerator.kt](../../app/src/main/java/com/example/aichat/story/BookGenerator.kt), outline 页 popup 菜单第 2 项触发:

1. 校验 session 至少 1 章 chapter
2. 弹进度对话框
3. 构造 system + user prompt 给主对话模型: "用 story tools 反向初始化 world / roles / volume / rules / foreshadow"
4. 直接调 [`ChatService.chat`](../../app/src/main/java/com/example/aichat/ChatService.kt) (out-of-band, 不入 session 历史)
5. ToolBridge `storyToolsEnabled = true`, 模型调 tool 全部落 SessionOutlineStore
6. `onToolCallStart` 回调实时更新进度文字
7. `onSuccess` 弹总结对话框: 新增条目数 + 工具调用列表

---

## 8. 后置工作 (S6 未完成项)

| 项                                          | 状态                     | 备注 |
| ------------------------------------------- | ------------------------ | ---- |
| `append_status_history` 真写 wi-server      | **本地 stub, 待加 endpoint** | 见 [STORY_MODE_PLAN.md §1.4](../../STORY_MODE_PLAN.md). 服务端表 `story_status_history(session_id, card_id, chapter, change, ts)`, 路由 `POST /api/story/status-history` / `GET ...?limit=20` |
| Subplot milestones UI 图形编辑               | 当前仅 metaJson 文本     | StoryEditDialogs 加列表编辑 |
| 角色关系矩阵可视化 (网格图)                 | 当前列表展示             | 列表已能用,矩阵进迭代 |
| Provider 白名单 (Gemini/Ollama 关 Story Tools) | 未加                     | 当前所有 provider 都注入,可能在弱 provider 上拉低成功率 |

---

## 9. 历史命名迁移

| 旧 type    | 新 type      | 自动迁移                                                          |
| ---------- | ------------ | ----------------------------------------------------------------- |
| `task`     | `roles`      | `SessionOutlineStore.getAll` 读时改写,写回                        |
| `material` | `foreshadow` | 同上                                                              |

老 inkos `inkosBookRulesYaml` 字段: Room v15→v16 迁移到 `_pending_inkos_rules_migration` 临时表, `AIChatApp.onCreate` → `InkosRulesMigration.runIfPending` 解析 YAML → 写 `type=rules` outline item → 清表。
