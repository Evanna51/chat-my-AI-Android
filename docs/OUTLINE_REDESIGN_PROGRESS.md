# 大纲系统重做 — 进度文档

> compact 后从这里恢复进度；每完成一项立刻勾选 + 记关键决策

## 用户最终需求

1. **大纲 Prompt 分类整理**：传给主模型的大纲按 章节/人物/世界/知情 分组归纳，不要混在一起。
2. **知情注入重做**：只读大纲（不读对话），按章节产出 AI 能强制遵守的知情约束；用户预览多选 → 加入 outline 或取消。
3. **删除泄密审计**：用户决定（事前注入约束更有用，事后审计意义不大）；不再优化，直接删。
4. **新增"生成卷纲"功能**：
   - 触发：每 10 章可生成一卷；总大纲超过 2k 字 / 15 章时建议生成
   - 内容：基于章节计划 + 知情 + 背景 + 人物
   - 默认 selected=true
   - selected：覆盖范围内的章节大纲不再发送，用卷纲替代
   - !selected：发送章节大纲，不发卷纲
   - 卷纲不出现在新增大纲的类型 chip 里（只能 AI 自动生成）
   - 列表展示时加「卷大纲」标签
   - 允许编辑/删除；编辑对话框不显示类型选择器
   - 新增 type = `volume`

## 任务清单（按推进顺序）

### Phase A — 公共工具准备
- [x] **A0** 设计 SessionOutlineItem 字段扩展：加 `selected: Boolean = true` + `volumeChapters: List<String> = []`（仅 volume 用）
- [x] **A1** SessionOutlineStore.normalizeType 接受 `volume`
- [x] **A2** 新增 `OutlinePromptBuilder` 工具：分类整理 outline 给模型；处理 volume 覆盖逻辑

### Phase B — 大纲 Prompt 分类整理（任务 1）
- [x] **B1** ChatSessionActivity.buildUserMessageForApi 重构：调用 OutlinePromptBuilder（按 章节 / 人物 / 世界 / 知情 / 资料 / 卷纲 分组；带 volume 覆盖与 selected 过滤）
- [x] **B2** 灰盒检查：grep 其它拼大纲的地方，统一走 OutlinePromptBuilder（确认仅 ChatSessionActivity 一处）

### Phase C — 删除泄密审计（任务 3）
- [x] **C1** SessionOutlineActivity 移除 「泄密审计」菜单项 + runLeakageAudit
- [x] **C2** ChatService.auditNovelLeakage 删除
- [x] **C3** strings.xml 清理 leak_audit_result_title / auditing_ai_response / error_no_knowledge_outline

### Phase D — 知情注入重做（任务 2）
- [x] **D1** ChatService.extractKnowledgeConstraints 重写：只吃大纲文本，输出 [{"chapter":"...","title":"...","content":"..."}]
- [x] **D2** SessionOutlineActivity.runKnowledgeExtraction 重写：调新接口 → 弹多选 dialog → 选中条目批量 outlineStore.add（title 形如 "[章节] 角色 - 信息点"）
- [x] **D3** dialog_knowledge_inject_preview.xml + item_knowledge_inject.xml + 内嵌 KnowledgeInjectAdapter

### Phase E — 卷大纲功能（任务 4）
- [x] **E1** SessionOutlineAdapter：识别 volume 类型，type 标签显示「卷大纲」
- [x] **E2** SessionOutlineActivity.showCreateDialog：ChipGroup 不加 volume（默认不加，未改动）
- [x] **E3** SessionOutlineActivity.showEditDialog：检测 volume 类型时走 showEditVolumeDialog（隐藏 chipGroupType + layoutKnowledgeScope）
- [x] **E4** ChatService.generateVolumeOutline(volumeTitle, coverageRange, promptContext, callback)
- [x] **E5** SessionOutlineActivity.runVolumeGeneration：起始/结束章节 Spinner → 调 service → outlineStore.add type=volume + 写 volumeChapters
- [x] **E6** OutlinePromptBuilder：volume 覆盖在 OutlinePromptBuilder.build 内实现（A2 完成）
- [x] **E7** SessionOutlineAdapter：volume 条目显示 MaterialSwitch；onSelectedChanged 回调 → outlineStore.setSelected
- [ ] **E8**（可选）自动建议：> 15 章 / > 2k 字 时建议生成 → 推迟独立任务

### Phase F — 验证
- [x] **F1** 编译通过（gradle compileDebugKotlin）
- [x] **F2** 全量 assembleDebug 通过

## 关键决策记录

- **E4 决策**：generateVolumeOutline 的 prompt 要求模型输出**纯文本**（不强 JSON），按章节区间总览输出，因为后续要让用户编辑/AI 复读，纯文本更友好。
- **E5 决策**：触发 UI = 选「起始章节」+「结束章节」（区间选择），生成 volume.title = "卷纲：{起始}~{结束}"，volumeChapters 存这个区间内全部 chapter title。
- **E6 决策**：volume 覆盖逻辑由 selected=true 的卷纲决定 — 凡 volumeChapters 里出现的 chapter title 都被屏蔽。多个 volume 的覆盖集合取并集。
- **B1 决策**：buildUserMessageForApi 不再内联拼接，转发到 OutlinePromptBuilder.buildOutlineSection(items)；以后所有需要往 prompt 注入大纲的地方都用这个。

## 文件变更清单
- 新增：[OutlinePromptBuilder.kt](app/src/main/java/com/example/aichat/OutlinePromptBuilder.kt)
- 新增：[dialog_knowledge_inject_preview.xml](app/src/main/res/layout/dialog_knowledge_inject_preview.xml)
- 修改：[SessionOutlineItem.kt](app/src/main/java/com/example/aichat/SessionOutlineItem.kt) — 加 `selected` + `volumeChapters` 字段
- 修改：[SessionOutlineStore.kt](app/src/main/java/com/example/aichat/SessionOutlineStore.kt) — normalizeType 接受 volume；增加 update 重载（保留 selected/volumeChapters）
- 修改：[SessionOutlineAdapter.kt](app/src/main/java/com/example/aichat/SessionOutlineAdapter.kt) — type label "卷大纲"，volume 行显示 selected 开关
- 修改：[ChatService.kt](app/src/main/java/com/example/aichat/ChatService.kt) — 删 auditNovelLeakage；改 extractKnowledgeConstraints；新增 generateVolumeOutline
- 修改：[ChatSessionActivity.kt](app/src/main/java/com/example/aichat/ChatSessionActivity.kt) — buildUserMessageForApi 走 OutlinePromptBuilder
- 修改：[SessionOutlineActivity.kt](app/src/main/java/com/example/aichat/SessionOutlineActivity.kt) — 删除泄密审计；重写 runKnowledgeExtraction；新增 runVolumeGeneration；编辑/创建对话框处理 volume；
- 修改：[strings.xml](app/src/main/res/values/strings.xml) — 删 leak_audit_result_title

## 复盘
- 顺序为什么这样：A（基础设施） → B（不破坏现状的 prompt 重构） → C（删功能简化代码） → D（在新基础上重做知情注入）→ E（卷大纲依赖 D 的预览模式 + B 的 builder）→ F（验证）。
- compact 后恢复指引：从最后一个 [x] 之后的第一个 [ ] 开始执行；同时检查"关键决策记录"是否需要新增条目。
