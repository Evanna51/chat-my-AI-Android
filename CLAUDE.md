# chatbox-android — 项目说明

## 项目简介

Android AI 聊天应用。支持多模型、多厂商（OpenAI、Claude 等），具备会话管理、助手系统、大纲生成、流式输出等功能。

---

## 当前主题系统

### 设计语言：iOS 2026 Inset Grouped + WhatsApp 聊天风格 + 液态玻璃（Liquid Glass）

UI 重设计计划详见 [UI_REDESIGN_PLAN.md](UI_REDESIGN_PLAN.md)。

---

### 颜色 Token（`values/colors.xml` + `values-night/colors.xml`）

#### iOS 2026 系统色
| Token | 亮色 | 暗色 |
|---|---|---|
| `ios_grouped_bg` | `#F2F2F7` | `#1C1C1E` |
| `ios_cell_bg` | `#FFFFFF` | `#2C2C2E` |
| `ios_separator` | `#C8C7CC` | `#38383A` |
| `ios_section_label` | `#6C6C70` | `#8E8E93` |
| `ios_destructive` | `#FF3B30` | `#FF453A` |

#### WhatsApp 气泡色
| Token | 亮色 | 暗色 |
|---|---|---|
| `whatsapp_bubble_user` | `#DCF8C6` | `#1E3A2A` |
| `whatsapp_bubble_assistant` | `#FFFFFF` | `#1C1F26` |
| `bubble_assistant_stroke` | `#E0E0E0` | `#2A2D35` |

#### 液态玻璃（Liquid Glass）
| Token | 亮色 | 暗色 |
|---|---|---|
| `glass_surface` | `#CCF2F2F7`（80% 透明） | `#CC1C1C1E` |
| `glass_stroke` | `#40FFFFFF` | `#30FFFFFF` |

#### 多主题色（Blue / Green / Purple / Orange）
每套主题色包含四个 token：`primary_*`、`primary_*_subtle`、`primary_*_light`、`primary_*_deep`，在 `themes.xml` 的 `Theme.AIChat.Blue/Green/Purple/Orange` 中映射为 `colorPrimary` 等属性。

---

### Drawable 资源

| 文件 | 用途 |
|---|---|
| `bg_glass_toolbar.xml` | 液态玻璃表面：半透明 + 白色细描边，圆角 16dp |
| `bg_glass_bottom_bar.xml` | 同上，无顶部圆角，用于底部输入栏 |
| `bubble_user_wa.xml` | WhatsApp 风格用户气泡：绿色，右下角 4dp |
| `bubble_assistant_wa.xml` | WhatsApp 风格助手气泡：白色，左下角 4dp |
| `bg_ios_dialog.xml` | iOS 风格对话框背景 |
| `bg_knowledge_picker.xml` | 知识库选择器背景 |

---

### Style 组件（`themes.xml`）

| Style | 用途 |
|---|---|
| `Widget.AIChat.IOSSectionHeader` | iOS section 标签：13sp、`ios_section_label` 色、padding 20/24/8dp |
| `Widget.AIChat.IOSListRow` | iOS 列表行：minHeight=56dp、水平 16dp padding、horizontal orientation |
| `Widget.AIChat.GlassCard` | 玻璃卡片：`glass_surface` 背景、4dp elevation、16dp 圆角、1dp 描边 |
| `Widget.AIChat.TextInputLayout` | 统一输入框：14dp 圆角、1/2dp 描边 |
| `ThemeOverlay.AIChat.MaterialAlertDialog` | 对话框 overlay：`dialog_surface_bg` 背景，无 elevation overlay |

---

### Liquid Glass 实现方式（Android 兼容）

**方案 A（当前实现，推荐）**：半透明背景 `glass_surface` + 1dp `glass_stroke` 描边 + 4dp elevation → 玻璃感，所有 API 版本兼容。

**方案 B（增强，暂未实现）**：`RenderEffect.createBlurEffect()` (API 31+) + 条件降级到方案 A。

---

## UI 重设计阶段进度

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 1 | Foundation（颜色/Drawable/Style） | **已完成** |
| Phase 2 | 首页（MainActivity）— 玻璃 Toolbar + WhatsApp 会话列表 | 进行中 |
| Phase 3 | 聊天页（ChatSessionActivity）— WhatsApp 气泡 + 玻璃输入栏 | 进行中 |
| Phase 4 | 全部对话（AllConversationsActivity） | 待开始 |
| Phase 5–11 | 其余页面（ModelConfig、Provider、SessionSettings 等） | 待开始 |

---

## 代码重构进度

详见 [REFACTOR_PROGRESS.md](REFACTOR_PROGRESS.md) 和 [KOTLIN_REFACTOR_PLAN.md](KOTLIN_REFACTOR_PLAN.md)。

- **SP → Room 迁移**：已完成
- **ChatSessionActivity → ViewModel**：已完成（step 2.11 待清理）
- **Java → Kotlin 全迁移**：已完成（当前分支 `refactor/java-to-kotlin`）
- **硬编码中文 → strings.xml**：85% 完成，ChatService 部分遗留

---

## 布局规范（新页面必须遵守）

1. 页面背景：`@color/ios_grouped_bg`
2. 列表/表单卡片：`MaterialCardView`，elevation=0，cornerRadius=16dp，背景 `ios_cell_bg`
3. Section 标签：使用 `style="@style/Widget.AIChat.IOSSectionHeader"`
4. 列表行：使用 `style="@style/Widget.AIChat.IOSListRow"`，minHeight=56dp
5. 分隔线：0.5dp，颜色 `ios_separator`
6. 玻璃效果区域（Toolbar/输入栏）：使用 `bg_glass_toolbar` 或 `bg_glass_bottom_bar`
7. 消息气泡：用 `bubble_user_wa` / `bubble_assistant_wa`，不用旧的 `user_bubble`/`assistant_bubble`
8. 破坏性操作（删除等）：颜色用 `ios_destructive`，不硬编码红色
9. 所有新增颜色必须同时在 `values-night/colors.xml` 提供暗色值
