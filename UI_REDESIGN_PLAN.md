# UI 全面重设计计划
**风格方向：iOS 2026 Inset Grouped + WhatsApp 聊天风格 + 液态玻璃（Liquid Glass）**

---

## 设计语言规范

### iOS 2026 基础
| Token | 值 |
|---|---|
| 页面背景 | `#F2F2F7` (ios_grouped_bg) |
| 卡片背景 | `#FFFFFF` (ios_cell_bg) |
| 分隔线 | 0.5dp, `#C8C7CC` (ios_separator) |
| Section 标签 | 13sp, `#6C6C70` |
| 行高 | minHeight=56dp |
| 卡片圆角 | 16dp，无 elevation |
| Chevron | 20dp, `#6C6C70` |

### WhatsApp 聊天风格
| 元素 | 样式 |
|---|---|
| 用户气泡 | 绿色 (`#DCF8C6`)，右对齐，右下圆角略小 |
| 助手气泡 | 白色 (`#FFFFFF`)，左对齐，左下圆角略小 |
| 会话列表行 | 头像(42dp圆形) + 加粗标题 + 副标题(最后一条) + 右侧时间戳 |
| 输入栏 | 圆角 EditText + 绿色发送 FAB |
| Toolbar | 深绿/主题色背景，白色文字 |

### 液态玻璃（Liquid Glass）
适用于：首页 Toolbar / Chat 输入栏 / 底部动作栏

Android 实现方式（API 31+ 兼容降级）：
- **方案 A（推荐）**：半透明背景 `#CCF2F2F7` + 1dp 白色边框 + 微 elevation（4dp）→ 玻璃感
- **方案 B（增强）**：`RenderEffect.createBlurEffect()` (API 31+) + 条件判断降级
- Toolbar 使用 `android:alpha` 渐变 + `windowTranslucentStatus=true`

---

## 阶段划分

---

## PHASE 1 — 设计基础（Foundation）

**目标**：新增颜色 token、drawable、dimension 资源，为后续所有页面提供复用素材。

### 1.1 `colors.xml` 补充
```
whatsapp_bubble_user    #DCF8C6
whatsapp_bubble_assistant #FFFFFF
glass_surface           #CCF2F2F7   (80% 透明 ios_grouped_bg)
glass_surface_dark      #CC1C1C1E   (暗色模式玻璃)
glass_stroke            #40FFFFFF
toolbar_tint_on_primary #FFFFFFFF
ios_destructive         #FF3B30
```

### 1.2 新增 Drawable
- `bg_glass_toolbar.xml` — 圆角 + 半透明白 + 细描边（用于首页搜索栏、聊天输入栏）
- `bg_glass_bottom_bar.xml` — 同上，无顶部圆角
- `bubble_user_wa.xml` — WhatsApp 绿色气泡（右对齐，右下角半圆形缩小）
- `bubble_assistant_wa.xml` — 白色气泡（左对齐，左下角半圆形缩小）

### 1.3 新增 Style（`styles.xml` 或 `themes.xml`）
- `Widget.App.GlassToolbar` — MaterialToolbar 子样式，半透明背景
- `Widget.App.IOSSection.Header` — TextView 13sp gray section label
- `Widget.App.IOSList.Row` — 行样式（56dp 高，水平 16dp padding）

---

## PHASE 2 — 首页（MainActivity）⭐ 高优先级

**目标**：玻璃 Toolbar + WhatsApp 会话列表 + 玻璃效果助手横滚栏

### 2.1 Toolbar / 搜索栏 → 液态玻璃
- 整体背景改为 `@color/ios_grouped_bg`
- 顶部搜索栏 `LinearLayout` 加 `bg_glass_toolbar` 背景（圆角 14dp，半透明白）
- 搜索框 `EditText` 背景透明，hint 颜色浅灰
- 设置按钮 icon tint 改为 `?attr/colorPrimary`
- 添加 `android:elevation="2dp"` 在滚动时产生玻璃浮起感（通过 RecyclerView scroll 监听动态设置）

### 2.2 会话列表 `item_conversation.xml` → WhatsApp 行
- 左侧：42dp 圆形头像（字母 + 主题色背景，或助手设定图片）
- 中间：
  - 标题行：会话名（`textAppearanceTitleMedium`，加粗）+ 右侧时间戳（12sp，灰色）
  - 副标题：最后一条消息截取前 40 字（`textAppearanceBodySmall`，灰色）
- 行背景：`ios_cell_bg` 白色，`selectableItemBackground` ripple
- 分组：按日期分 section（今天 / 本周 / 更早），section header 用 iOS 13sp 标签

### 2.3 "我的助手" 横滚区域 → 玻璃卡片
- 背景改为半透明白 `bg_glass_toolbar`（圆角 16dp，margin 水平 16dp）
- 每个助手 item：圆形头像（48dp） + 名字（10sp，居中）
- Header 标签"我的助手"使用 iOS section 样式

### 2.4 底部输入区移除（首页无输入区）
- 确认首页无聊天输入框，仅有 FAB 或列表入口

---

## PHASE 3 — 聊天页（ChatSessionActivity）⭐ 高优先级

**目标**：WhatsApp 气泡 + 玻璃输入栏 + Toolbar 优化

### 3.1 消息气泡 `item_message_user.xml`
- 背景换用 `bubble_user_wa.xml`（`#DCF8C6` 绿色，右下角 4dp，其余 16dp）
- 气泡最大宽度 280dp → 72% 屏幕宽（更自然）
- 底部操作按钮（编辑/复制/删除）统一使用 `ios_section_label` 灰色 icon，取消 hardcoded 红色

### 3.2 消息气泡 `item_message_assistant.xml`
- 背景换用 `bubble_assistant_wa.xml`（纯白，左下角 4dp，其余 16dp）
- 加 `elevation="1dp"` 或 `strokeWidth="0.5dp"` 配合微描边（`#E0E0E0`）增加层次
- Thinking 区域：`#F5F5F5` 背景，13sp 斜体文字，视觉上更内敛
- 删除按钮 hardcoded 红色 → 改用 `@color/ios_destructive`

### 3.3 底部输入栏 → 液态玻璃
- 整体背景改为 `bg_glass_bottom_bar`（80% 透明白，上边框 0.5dp `ios_separator`）
- `EditText` 气泡圆角样式（`bg_glass_toolbar`，圆角 24dp）
- 发送按钮改为圆形 FAB 样式（40dp，`colorPrimary` 背景，白色图标）
- 加号按钮区：图标统一大小（24dp），去掉多余 padding

### 3.4 Toolbar
- 高度改为标准 `?attr/actionBarSize`
- 左侧：返回 + 助手头像（32dp 圆形）+ 名字（加粗）
- 右侧：大纲、设置、更多 三个 icon（统一 24dp，白色 tint）
- 背景：`?attr/colorPrimary`（深绿/主题色），白色文字，elevation=0，与玻璃滚动区形成对比

---

## PHASE 4 — 全部对话（AllConversationsActivity）

**目标**：iOS grouped list，与首页会话列表视觉统一

### 4.1
- Toolbar 背景 `ios_grouped_bg`，elevation=0
- 列表背景 `ios_grouped_bg`
- 复用 PHASE 2.2 的 WhatsApp 行样式
- 增加搜索栏（玻璃卡片样式）在 RecyclerView 上方

---

## PHASE 5 — 模型配置（ModelConfigActivity）

**目标**：iOS grouped form，去掉 pseudo-card LinearLayouts

### 5.1
- 整体改为 `ios_grouped_bg` 背景
- 5 个模型选择行合并为单个 `MaterialCardView`（elevation=0, cornerRadius=16dp, bg=ios_cell_bg）
- 每行：16dp 水平 padding，56dp 高，右侧显示当前选中模型名（灰色），chevron
- Stream 切换改为 iOS style row with MaterialSwitch（右侧）
- 保存按钮改为全宽 `MaterialButton`（primary，16dp 圆角，margin 16dp）

---

## PHASE 6 — 厂商详情（ProviderDetailActivity）

**目标**：iOS form + 模型列表 iOS grouped list

### 6.1
- Toolbar 背景 `ios_grouped_bg`，elevation=0
- TextInputLayout 字段放入单个白色卡片（cornerRadius=16dp），字段间 0.5dp 分隔线
- "获取模型"和"删除厂商"两个按钮分别用 iOS 样式（primary 和 destructive）
- 模型列表（已添加 / 可添加）分为两个独立 grouped card section

---

## PHASE 7 — 当前对话设置（SessionChatSettingsActivity）

**目标**：iOS grouped 表单

### 7.1
- 背景 `ios_grouped_bg`
- 字段拆分：会话信息一组（标题、头像）、模型参数一组（temperature、top_p等）、开关一组（stream、thinking）
- 每组一个 grouped card，section header 用 iOS 标签
- 头像选择器改为圆形点击区 + 文字提示（iOS picker 样式）

---

## PHASE 8 — 我的助手（MyAssistantsActivity）

**目标**：iOS grouped list + WhatsApp 联系人卡片感

### 8.1
- 列表项：圆形头像（42dp）+ 名字 + 类型标签（角标/徽章）
- 新建按钮改为 FAB（右下角）+ 顶部虚线占位行（iOS 风格"添加"行）
- Section header 分组：默认助手 / 自定义助手

---

## PHASE 9 — 编辑助手（EditMyAssistantActivity）

**目标**：iOS form，头像选择 iOS picker 样式

### 9.1
- 背景 `ios_grouped_bg`
- 头像区域：居中圆形图片（80dp）+ "点击更换"文字（iOS profile 编辑样式）
- 名字、类型等字段按逻辑分组放入 grouped cards
- 删除按钮：独立一个 grouped card（单行），文字颜色 `ios_destructive`（红色），无 icon

---

## PHASE 10 — 大纲（SessionOutlineActivity）

**目标**：iOS 列表 + 底部双按钮 iOS 样式

### 10.1
- 背景 `ios_grouped_bg`
- 大纲列表改为 grouped card（section 标签：共 N 章）
- 底部两个按钮：`新增大纲`（filled primary）+ `泄密审计`（text/outlined），统一 16dp 圆角

---

## PHASE 11 — 修复 ConfigActivity

**目标**：修复 Toolbar 在 ScrollView 内的问题

### 11.1
- 将 `MaterialToolbar` 移出 `ScrollView`，放在根 LinearLayout 顶部
- 表单字段放入 grouped card（同 PHASE 6 风格）

---

## 执行顺序

```
Phase 1 (Foundation) → Phase 2 (Main) → Phase 3 (Chat)
    → Phase 4 (AllConversations) → Phase 5 (ModelConfig)
    → Phase 6 (ProviderDetail) → Phase 7 (SessionChatSettings)
    → Phase 8 (MyAssistants) → Phase 9 (EditMyAssistant)
    → Phase 10 (Outline) → Phase 11 (ConfigFix)
```

---

## 注意事项

- **气泡最大宽度**：用 `android:layout_width="wrap_content"` + `android:maxWidth` 属性，不硬编码 280dp
- **液态玻璃降级**：API < 31 时降级为纯半透明背景，不 crash
- **暗色模式**：所有新增颜色需在 `values-night/colors.xml` 中提供暗色对应值
- **item_provider.xml** ✅ 已完成（PHASE 2 前已修复）
- **activity_settings.xml** ✅ 已完成（本计划参考基准）

---

*创建于 2026-04-12*
