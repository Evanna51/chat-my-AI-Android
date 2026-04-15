# 风格优化手册 — Style Optimization Manual

**目标**：将当前 XML 静态模拟的「液态玻璃」升级为真正具备 iOS 精髓的动态玻璃系统，统一图标语言，优化交互合理性，丰富可操作功能。

**适用范围**：chatbox-android 全部 13 个 Activity 及其布局、drawable、自定义 View。

---

## 目录

1. [现状诊断](#1-现状诊断)
2. [图标风格化统一](#2-图标风格化统一)
3. [液态玻璃系统升级](#3-液态玻璃系统升级)
4. [边缘高光系统](#4-边缘高光系统)
5. [动态响应系统](#5-动态响应系统)
6. [环境污染色彩系统](#6-环境污染色彩系统)
7. [分层空间感](#7-分层空间感)
8. [操作合理性优化](#8-操作合理性优化)
9. [功能丰富化](#9-功能丰富化)
10. [深浅色模式适配标准](#10-深浅色模式适配标准)
11. [实施计划与优先级](#11-实施计划与优先级)
12. [验收标准](#12-验收标准)

---

## 1. 现状诊断

### 1.1 已完成的基础

| 方面 | 状态 | 说明 |
|---|---|---|
| 颜色 Token | 已建立 | iOS 系统色 / WhatsApp 气泡色 / Glass 色 / 四套主题色 |
| 玻璃 Drawable | 已建立 | `bg_glass_toolbar.xml`、`bg_glass_bottom_bar.xml` |
| 气泡 Drawable | 已建立 | WhatsApp 风格 `bubble_user_wa` / `bubble_assistant_wa` |
| 主题系统 | 已建立 | Material3 Day/Night + Blue/Green/Purple/Orange 四色 |

### 1.2 待解决的问题

| 问题 | 现状 | 目标 |
|---|---|---|
| 玻璃效果 | 纯 XML 半透明遮罩，无模糊 | 真实背景模糊 + 饱和度 + 亮度微调 |
| 边缘高光 | 仅 1dp 白色 `glass_stroke` | 上亮下暗渐变高光 + 内阴影 |
| 动态响应 | 无 | 滚动时模糊强度变化、elevation 动态响应 |
| 环境色 | 固定 `#CCF2F2F7` | 背景内容采样，玻璃色彩随背景变化 |
| 图标风格 | 混杂（filled / stroke / 不同粗细） | 统一 SF Symbols 风格线性图标 |
| 操作栏 | 展开/折叠逻辑混乱，按钮冗余 | 分级操作：常驻 / 长按 / 滑动 |
| 空间层次 | elevation 平铺无层次 | 清晰的 Z 轴分层（背景 → 内容 → 玻璃 → 控件） |

---

## 2. 图标风格化统一

### 2.1 设计标准：SF Symbols 线性风格

所有图标统一为以下规范：

| 属性 | 标准值 | 说明 |
|---|---|---|
| 画布尺寸 | 24dp × 24dp | `viewportWidth/Height = 24` |
| 线条粗细 | `strokeWidth="1.5"` | 统一 1.5dp（当前混用 1.2 / 2.0） |
| 线帽 | `strokeLineCap="round"` | 圆角线帽，iOS 风格 |
| 线连接 | `strokeLineJoin="round"` | 圆角连接 |
| 填充方式 | `fillColor="transparent"` + stroke | 线性描边，不填充（outline 风格） |
| 颜色 | `strokeColor="#FF000000"` | 固定黑色，运行时通过 `tint` 着色 |
| 视觉重量 | 均匀一致 | 同尺寸图标视觉粗细一致 |

### 2.2 需修正的图标

| 图标 | 当前问题 | 修正方案 |
|---|---|---|
| `ic_search.xml` | **filled** 风格（`fillColor=black`，无 stroke） | 改为 stroke 线性风格，strokeWidth=1.5 |
| `ic_action_copy.xml` | strokeWidth=1.2（偏细） | 统一为 1.5 |
| `ic_action_delete.xml` | strokeWidth=1.2 | 统一为 1.5 |
| `ic_action_edit.xml` | strokeWidth=1.2 | 统一为 1.5 |
| `ic_action_menu.xml` | strokeWidth=2.0（偏粗） | 统一为 1.5 |
| `ic_action_hide.xml` | strokeWidth=1.5（已正确） | 保持 |
| `ic_chevron_right.xml` | 检查是否 stroke 风格 | 统一 |
| `ic_add.xml` | 检查粗细 | 统一为 1.5 |

### 2.3 新增图标需求

以下场景当前缺少专用图标，需按统一风格新增：

| 用途 | 建议图标名 | 参考 SF Symbol |
|---|---|---|
| 分享/导出 | `ic_action_share.xml` | `square.and.arrow.up` |
| 语音输入 | `ic_action_mic.xml` | `mic` |
| 图片附件 | `ic_action_image.xml` | `photo` |
| 文件附件 | `ic_action_file.xml` | `doc` |
| 标记/书签 | `ic_action_bookmark.xml` | `bookmark` |
| 翻译 | `ic_action_translate.xml` | `character.bubble` |
| 朗读 | `ic_action_speaker.xml` | `speaker.wave.2` |
| 引用回复 | `ic_action_quote.xml` | `text.quote` |
| 选择文本 | `ic_action_select.xml` | `text.cursor` |
| 格式化 | `ic_action_format.xml` | `textformat` |

### 2.4 图标着色规范

| 场景 | tint 颜色 |
|---|---|
| Toolbar 图标（主题色背景上） | `#FFFFFF`（白色） |
| 操作栏常规图标 | `@color/ios_section_label`（`#6C6C70` / `#8E8E93`） |
| 操作栏高亮/主要操作 | `?attr/colorPrimary` |
| 破坏性操作（删除） | `@color/ios_destructive` |
| 禁用状态 | `@color/text_quaternary`（30% 不透明度） |

---

## 3. 液态玻璃系统升级

### 3.1 架构设计：LiquidGlassView

创建自定义 View 组件 `LiquidGlassView`，替代当前的 XML drawable 方案。

```
层次结构（由底向上）：
┌─────────────────────────────────────┐
│  Layer 5: 前景内容（文字、图标）        │
├─────────────────────────────────────┤
│  Layer 4: 边缘高光（上亮下暗渐变）      │
├─────────────────────────────────────┤
│  Layer 3: 着色层（环境色 tint）        │
├─────────────────────────────────────┤
│  Layer 2: 饱和度 + 亮度调整            │
├─────────────────────────────────────┤
│  Layer 1: 背景模糊（RenderEffect）     │
├─────────────────────────────────────┤
│  Layer 0: 背景内容（穿透可见）          │
└─────────────────────────────────────┘
```

### 3.2 实现方案（三级降级）

#### Tier 1：API 31+（Android 12+）— 完整玻璃

```kotlin
// 核心实现伪代码
class LiquidGlassView : FrameLayout {

    // 模糊效果
    private val blurEffect = RenderEffect.createBlurEffect(
        blurRadiusX,  // 动态值，默认 25f
        blurRadiusY,
        Shader.TileMode.CLAMP
    )

    // 饱和度提升 (1.2x ~ 1.8x)
    private val saturationMatrix = ColorMatrix().apply {
        setSaturation(1.5f)
    }
    private val saturationEffect = RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(saturationMatrix)
    )

    // 亮度微调 (亮色模式 +5%，暗色模式 -3%)
    private val brightnessMatrix = ColorMatrix(floatArrayOf(
        1.05f, 0f, 0f, 0f, 0f,  // 亮色模式
        0f, 1.05f, 0f, 0f, 0f,
        0f, 0f, 1.05f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))

    // 链式组合
    val chainedEffect = RenderEffect.createChainEffect(
        RenderEffect.createChainEffect(blurEffect, saturationEffect),
        brightnessEffect
    )
}
```

**关键参数**：

| 参数 | 亮色模式 | 暗色模式 |
|---|---|---|
| 模糊半径 | 25dp | 20dp |
| 饱和度倍率 | 1.5x | 1.2x |
| 亮度偏移 | +5% | -3% |
| 背景 tint | `#80F2F2F7` (50% 白灰) | `#801C1C1E` (50% 深灰) |
| 描边 | `#40FFFFFF` (25% 白) | `#20FFFFFF` (12% 白) |

#### Tier 2：API 26-30 — 增强模拟

无 `RenderEffect` 支持，使用替代方案：

- **背景截取模糊**：`Bitmap` 截取 → `RenderScript.blur()` (API 26+) 或 Toolkit blur
- **饱和度 / 亮度**：通过 `ColorMatrix` + `Paint.setColorFilter` 在 Canvas 上绘制
- 帧率限制：非滚动状态下不重绘，滚动时 30fps 上限

#### Tier 3：API < 26 — 静态降级（当前方案保留）

- 保留现有 `bg_glass_toolbar.xml` / `bg_glass_bottom_bar.xml`
- 半透明 `glass_surface` 背景 + 1dp `glass_stroke` 描边
- 无模糊、无饱和度调整

### 3.3 自定义属性

```xml
<declare-styleable name="LiquidGlassView">
    <!-- 模糊半径 (dp)，默认 25 -->
    <attr name="glassBlurRadius" format="dimension" />
    <!-- 饱和度倍率，默认 1.5 -->
    <attr name="glassSaturation" format="float" />
    <!-- 亮度偏移 (-1.0 ~ 1.0)，默认 0.05 -->
    <attr name="glassBrightnessOffset" format="float" />
    <!-- 环境色 tint，默认 glass_surface -->
    <attr name="glassTintColor" format="color" />
    <!-- 描边颜色，默认 glass_stroke -->
    <attr name="glassStrokeColor" format="color" />
    <!-- 描边宽度，默认 1dp -->
    <attr name="glassStrokeWidth" format="dimension" />
    <!-- 圆角半径，默认 16dp -->
    <attr name="glassCornerRadius" format="dimension" />
    <!-- 是否启用边缘高光，默认 true -->
    <attr name="glassEdgeHighlight" format="boolean" />
    <!-- 是否启用动态响应（滚动联动），默认 false -->
    <attr name="glassDynamicResponse" format="boolean" />
</declare-styleable>
```

### 3.4 应用位置

| 位置 | 圆角 | 模糊半径 | 特殊配置 |
|---|---|---|---|
| 首页搜索栏 | 16dp 全圆角 | 25dp | 带边缘高光 |
| 聊天输入栏 | 0dp（无圆角） | 20dp | 顶部 0.5dp 分隔线 |
| 对话框背景 | 28dp | 30dp | 更强模糊，更柔和 tint |
| 浮动操作菜单 | 16dp | 20dp | 带 elevation 阴影 |
| 底部 Sheet | 顶部 20dp | 25dp | 带手柄指示器 |

---

## 4. 边缘高光系统

### 4.1 原理

iOS 液态玻璃的关键细节：**边缘不是均匀的描边，而是有方向性的光感**。

```
       ╭──── 亮（上边缘高光，模拟光源从上方照射）
       │
  ┌────┴────────────────────┐
  │                          │
  │     Glass Surface        │
  │                          │
  └────┬────────────────────┘
       │
       ╰──── 暗（下边缘阴影，自然下沉感）
```

### 4.2 实现方案

#### 方案 A：渐变描边 Drawable（XML 可实现）

```xml
<!-- bg_glass_edge_highlight.xml -->
<layer-list>
    <!-- 底层：玻璃表面 -->
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/glass_surface" />
            <corners android:radius="16dp" />
        </shape>
    </item>

    <!-- 上边缘高光：白色渐变，从上往下消失 -->
    <item android:bottom="@dimen/glass_highlight_fade_height">
        <shape android:shape="rectangle">
            <gradient
                android:startColor="@color/glass_highlight_top"
                android:endColor="@android:color/transparent"
                android:angle="270" />
            <corners android:topLeftRadius="16dp" android:topRightRadius="16dp" />
        </shape>
    </item>

    <!-- 下边缘阴影：深色渐变，从下往上消失 -->
    <item android:top="@dimen/glass_shadow_fade_height">
        <shape android:shape="rectangle">
            <gradient
                android:startColor="@android:color/transparent"
                android:endColor="@color/glass_shadow_bottom"
                android:angle="270" />
            <corners android:bottomLeftRadius="16dp" android:bottomRightRadius="16dp" />
        </shape>
    </item>

    <!-- 描边（整体） -->
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@android:color/transparent" />
            <stroke android:width="0.5dp" android:color="@color/glass_stroke" />
            <corners android:radius="16dp" />
        </shape>
    </item>
</layer-list>
```

#### 方案 B：Canvas 自定义绘制（推荐，精确控制）

在 `LiquidGlassView.onDraw()` 中：

```kotlin
// 上边缘高光
val highlightPaint = Paint().apply {
    shader = LinearGradient(
        0f, 0f, 0f, highlightHeight,
        intArrayOf(highlightColorTop, Color.TRANSPARENT),
        null, Shader.TileMode.CLAMP
    )
}
canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, highlightPaint)

// 下边缘内阴影
val shadowPaint = Paint().apply {
    shader = LinearGradient(
        0f, height - shadowHeight, 0f, height.toFloat(),
        intArrayOf(Color.TRANSPARENT, shadowColorBottom),
        null, Shader.TileMode.CLAMP
    )
}
canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, shadowPaint)
```

### 4.3 颜色 Token

| Token | 亮色 | 暗色 | 用途 |
|---|---|---|---|
| `glass_highlight_top` | `#30FFFFFF` | `#15FFFFFF` | 上边缘高光起始色 |
| `glass_shadow_bottom` | `#10000000` | `#20000000` | 下边缘阴影终止色 |
| `glass_inner_glow` | `#08FFFFFF` | `#05FFFFFF` | 内部微光（可选） |

### 4.4 高光参数标准

| 参数 | 值 |
|---|---|
| 高光渐变高度 | 玻璃组件总高度的 30% ~ 40% |
| 阴影渐变高度 | 玻璃组件总高度的 15% ~ 20% |
| 高光最大不透明度 | 亮色 19%（`#30`），暗色 8%（`#15`） |
| 阴影最大不透明度 | 亮色 6%（`#10`），暗色 12%（`#20`） |

---

## 5. 动态响应系统

### 5.1 滚动联动模糊

核心理念：**滚动时模糊强度变化，制造玻璃下方内容「流动」的感觉**。

```kotlin
// ChatSessionActivity / MainActivity
recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
        val scrollOffset = rv.computeVerticalScrollOffset()
        val maxScroll = 300f // dp → px

        // 模糊强度：静止 20dp → 滚动 30dp
        val blurRadius = lerp(20f, 30f, (scrollOffset / maxScroll).coerceIn(0f, 1f))
        glassToolbar.setBlurRadius(blurRadius)

        // Elevation：静止 0dp → 滚动 4dp（玻璃浮起）
        val elevation = lerp(0f, 4f.dpToPx(), (scrollOffset / maxScroll).coerceIn(0f, 1f))
        glassToolbar.elevation = elevation

        // 高光强度：滚动时高光更明显（光源变化感）
        val highlightAlpha = lerp(0.12f, 0.25f, (scrollOffset / maxScroll).coerceIn(0f, 1f))
        glassToolbar.setHighlightAlpha(highlightAlpha)
    }
})
```

### 5.2 键盘弹起联动

```kotlin
// 聊天页：键盘弹出时输入栏模糊减弱（焦点转移到输入）
ViewCompat.setOnApplyWindowInsetsListener(inputBar) { view, insets ->
    val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
    val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom

    if (imeVisible) {
        // 键盘弹出：模糊降低，tint 加深（聚焦感）
        inputBarGlass.animateBlurRadius(25f, 15f, duration = 200)
        inputBarGlass.animateTintAlpha(0.5f, 0.7f, duration = 200)
    } else {
        // 键盘收起：恢复
        inputBarGlass.animateBlurRadius(15f, 25f, duration = 200)
        inputBarGlass.animateTintAlpha(0.7f, 0.5f, duration = 200)
    }
    insets
}
```

### 5.3 触摸反馈

```kotlin
// 玻璃按钮 / 玻璃卡片的按压响应
glassCard.setOnTouchListener { view, event ->
    when (event.action) {
        MotionEvent.ACTION_DOWN -> {
            // 按压：模糊加强 + 亮度微降 + 缩放 0.97
            view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start()
            (view as? LiquidGlassView)?.animateBrightness(0.05f, -0.02f, 100)
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            // 释放：恢复
            view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            (view as? LiquidGlassView)?.animateBrightness(-0.02f, 0.05f, 150)
        }
    }
    false
}
```

### 5.4 动画参数标准

| 交互 | 参数变化 | 时长 | 插值器 |
|---|---|---|---|
| 滚动 → 模糊 | 20dp → 30dp | 实时跟随 | 线性 |
| 滚动 → elevation | 0dp → 4dp | 实时跟随 | 线性 |
| 键盘弹出 | blur 25→15, tint +20% | 200ms | DecelerateInterpolator |
| 按压 | scale 0.97, brightness -7% | 100ms | AccelerateDecelerateInterpolator |
| 释放 | 恢复 | 150ms | OvershootInterpolator(1.2) |
| 页面切换 | blur 0→25（渐入） | 300ms | DecelerateInterpolator |

---

## 6. 环境污染色彩系统

### 6.1 原理

iOS 的「液态玻璃」不是纯白/纯黑遮罩 —— **玻璃会被背景"污染"**，带上背景的色调。

```
┌──────────────────────────────┐
│    蓝色背景区域                │
│  ┌────────────────────┐      │
│  │  玻璃（带淡蓝色调）  │      │  ← 玻璃吸收了蓝色
│  └────────────────────┘      │
├──────────────────────────────┤
│    橙色背景区域                │
│  ┌────────────────────────┐  │
│  │  玻璃（带淡橙色调）     │  │  ← 玻璃吸收了橙色
│  └────────────────────────┘  │
└──────────────────────────────┘
```

### 6.2 实现方案

#### 静态方案（推荐初期实现）

基于当前主题色，玻璃 tint 预设环境色：

| 主题 | 玻璃 tint（亮色） | 玻璃 tint（暗色） |
|---|---|---|
| Blue | `#08448AFF`（极淡蓝） | `#0C448AFF` |
| Green | `#082E7D32`（极淡绿） | `#0C2E7D32` |
| Purple | `#086A1B9A`（极淡紫） | `#0C6A1B9A` |
| Orange | `#08EF6C00`（极淡橙） | `#0CEF6C00` |

在 `LiquidGlassView` 绘制时叠加一层 tint：

```kotlin
// 在模糊层之上、高光层之下
val tintPaint = Paint().apply {
    color = resolveThemeTintColor()  // 从当前主题获取
    alpha = tintAlpha  // 3%~8% 不透明度
}
canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, tintPaint)
```

#### 动态方案（进阶实现）

实时采样背景区域主色调：

```kotlin
// 每次布局变化或内容滚动时
fun sampleBackgroundColor(): Int {
    // 1. 截取当前 View 区域下方的 Bitmap
    val bitmap = captureRegionBelowView()
    // 2. 缩小到 1x1 采样平均色
    val scaled = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
    val dominantColor = scaled.getPixel(0, 0)
    // 3. 降低饱和度 + 降低不透明度 → 环境 tint
    return adjustForTint(dominantColor, saturation = 0.3f, alpha = 0.06f)
}
```

**性能约束**：
- 采样频率：最多每帧一次（16ms），滚动时降为每 3 帧
- Bitmap 尺寸：最大 32x32 缩略（够用于提取主色调）
- 非前台时停止采样

### 6.3 环境色参数标准

| 参数 | 值 |
|---|---|
| tint 不透明度 | 3% ~ 8%（玻璃面积越大，越淡） |
| 采样饱和度调整 | 原始饱和度 × 0.3（避免过于艳丽） |
| 色相偏移 | 无（保留原始色相） |
| 过渡动画 | 500ms，用于颜色平滑切换 |

---

## 7. 分层空间感

### 7.1 Z 轴层级定义

从后到前，共 5 层：

| 层级 | Z-index (elevation) | 内容 | 示例 |
|---|---|---|---|
| L0 — 背景层 | 0dp | 页面底色、壁纸、渐变 | `ios_grouped_bg`，聊天背景 |
| L1 — 内容层 | 0dp~1dp | 列表卡片、消息气泡 | `ios_cell_bg` 卡片，WhatsApp 气泡 |
| L2 — 玻璃层 | 2dp~6dp | Toolbar、输入栏、浮动面板 | `LiquidGlassView` 组件 |
| L3 — 控件层 | 6dp~8dp | FAB、弹出菜单、下拉选择 | 发送按钮、操作菜单 |
| L4 — 对话框层 | 12dp~24dp | Dialog、BottomSheet、Toast | 确认框、编辑对话框 |

### 7.2 Elevation 标准

| 组件 | elevation | 补充效果 |
|---|---|---|
| 消息气泡（助手） | 0.5dp | 0.5dp stroke 替代 |
| 消息气泡（用户） | 0dp | 无阴影（贴合感） |
| 普通卡片（列表项） | 0dp | 仅用 stroke 分隔 |
| 玻璃 Toolbar（静止） | 0dp | 边缘高光 |
| 玻璃 Toolbar（滚动中） | 4dp | 动态提升 + 边缘高光 |
| 玻璃输入栏 | 2dp | 固定，上边缘 separator |
| FAB 发送按钮 | 6dp | 默认 Material 阴影 |
| 弹出操作菜单 | 8dp | 带玻璃模糊 |
| Dialog | 16dp | 强模糊（30dp radius） |
| BottomSheet | 12dp | 带手柄 + 玻璃模糊 |

### 7.3 阴影色彩（非纯黑）

Material Design 默认阴影是纯黑色。iOS 风格需要 **带色调的阴影**：

| 模式 | 阴影色 | 说明 |
|---|---|---|
| 亮色 | `#15000020`（极淡蓝黑） | 微微偏冷 |
| 暗色 | `#30000000`（纯黑加深） | 不偏色 |

实现方式（API 28+）：

```kotlin
// OutlineProvider + spotShadowAlpha
view.outlineSpotShadowColor = Color.parseColor("#15000020")
view.outlineAmbientShadowColor = Color.parseColor("#10000020")
```

---

## 8. 操作合理性优化

### 8.1 消息操作分级

当前问题：所有操作按钮平铺展示，展开/折叠逻辑不清晰。

#### 新方案：三级操作层

```
┌─────────────────────────────────────────────────┐
│ 第一级：常驻（气泡下方直接可见，最多 3 个）        │
│   用户气泡：  [复制] [编辑] [...]                 │
│   助手气泡：  [复制] [朗读] [...]                 │
├─────────────────────────────────────────────────┤
│ 第二级：展开菜单（点击 [...] 弹出）               │
│   [重新生成] [引用回复] [选择文本]                │
│   [翻译] [添加大纲] [分享]                       │
├─────────────────────────────────────────────────┤
│ 第三级：长按/滑动（破坏性操作）                    │
│   [删除消息]（iOS destructive 红色确认）          │
└─────────────────────────────────────────────────┘
```

### 8.2 操作栏样式标准

```
常驻操作栏样式：
┌──────────────────────────┐
│  📋 复制  ✏️ 编辑  ••• │   ← 胶囊形背景 pill
└──────────────────────────┘

展开菜单样式（玻璃材质弹出）：
╭──────────────────╮
│  🔄 重新生成      │
│  💬 引用回复      │
│  📝 选择文本      │
│  🌐 翻译          │
│  📑 添加大纲      │
│  ↗️ 分享          │
╰──────────────────╯
```

（注：上方图示中的 emoji 仅用于说明，实际使用统一风格的 SVG 图标）

### 8.3 操作栏视觉规范

| 属性 | 值 |
|---|---|
| 容器形状 | 胶囊形（999dp radius），玻璃材质 |
| 容器背景 | `glass_surface` + 0.5dp `glass_stroke` |
| 按钮尺寸 | 32dp × 32dp 点击区域，20dp 图标 |
| 按钮间距 | 4dp |
| 文字 | 可选，11sp，`ios_section_label` 色 |
| 位置 | 气泡下方，左对齐（助手）/ 右对齐（用户） |
| 出现动画 | 200ms fade + slide up 4dp |
| 展开菜单 | 向上弹出，玻璃材质，8dp 圆角，8dp 阴影 |

### 8.4 会话列表操作

当前：点击隐藏按钮 + 点击删除按钮（直接暴露）

优化方案：

| 操作方式 | 触发动作 |
|---|---|
| 单击 | 打开会话 |
| 左滑（短） | 露出 [置顶] [隐藏] |
| 左滑（长） | 露出 [删除]（红色背景） |
| 长按 | 弹出 iOS Action Sheet：置顶 / 隐藏 / 重命名 / 导出 / 删除 |

### 8.5 Toolbar 操作精简

当前聊天页 Toolbar 有 3 个按钮（大纲、设置、更多），不够直觉。

优化方案：

```
┌──────────────────────────────────────────┐
│ ← [助手头像 名字 ▾]           [搜索] [⋯] │
│    (点击展开模型信息)                      │
└──────────────────────────────────────────┘

[⋯] 菜单展开（玻璃 Action Sheet）：
  ┌──────────────┐
  │ 📊 大纲       │
  │ ⚙️ 会话设置    │
  │ 📤 导出       │
  │ 🔍 搜索消息    │
  │ 🗑 清空会话    │  ← ios_destructive 红色
  └──────────────┘
```

---

## 9. 功能丰富化

### 9.1 新增功能清单

| 功能 | 位置 | 优先级 | 说明 |
|---|---|---|---|
| 消息搜索 | 聊天页 Toolbar | P1 | 搜索当前会话中的消息 |
| 引用回复 | 消息操作菜单 | P1 | 长按消息 → 引用，输入框显示引用预览 |
| 消息朗读（TTS） | 助手消息操作 | P2 | 朗读 AI 回复，支持暂停/继续 |
| 翻译 | 消息操作菜单 | P2 | 调用 AI 翻译选中消息 |
| 多选模式 | 长按消息进入 | P2 | 批量复制 / 删除 / 导出 / 分享 |
| 消息书签 | 消息操作菜单 | P3 | 标记重要消息，在大纲中筛选 |
| 快捷回复 | 输入栏上方 | P3 | AI 建议的快速回复选项 |
| 语音输入 | 输入栏按钮 | P3 | 语音转文字输入 |
| 图片/文件附件 | 输入栏加号菜单 | P2 | 支持发送图片、文件给多模态模型 |
| 会话置顶 | 首页会话列表 | P1 | 置顶重要会话 |
| 会话分组/标签 | 首页 | P3 | 按标签分组会话 |
| 拖拽排序 | 首页会话列表 | P3 | 长按拖拽调整会话顺序 |

### 9.2 新功能的 UI 规范

所有新功能 UI 必须遵循以下标准：

1. **弹出菜单** → 使用 `LiquidGlassView` 作为容器，不使用默认 `PopupMenu`
2. **新增按钮** → 遵循统一图标规范（SF Symbols 风格，strokeWidth=1.5）
3. **新增页面** → 遵循 iOS grouped list 布局规范（见 CLAUDE.md 布局规范章节）
4. **新增对话框** → 使用 iOS 风格圆角对话框（28dp 圆角，`bg_ios_dialog`，按钮左右排列）
5. **新增动画** → 参考第 5 节动态响应系统的参数标准

---

## 10. 深浅色模式适配标准

### 10.1 色彩适配规则

| 元素 | 亮色原则 | 暗色原则 |
|---|---|---|
| 背景 | 冷灰白系（`#F2F2F7`） | 深冷灰系（`#1C1C1E`），不纯黑 |
| 表面 | 纯白（`#FFFFFF`） | 提升一级灰（`#2C2C2E`） |
| 玻璃 | 白灰半透明（80%） | 深灰半透明（80%） |
| 文字 | 黑色 → 四级递减透明度 | 白色 → 四级递减透明度 |
| 图标 | 黑/灰色 tint | 白/浅灰色 tint |
| 阴影 | 可见但柔和 | 几乎不可见（靠 stroke 补偿） |
| 边缘高光 | 白色高光较明显 | 白色高光极微弱 |
| 分隔线 | `#C8C7CC` | `#38383A` |

### 10.2 玻璃在暗色模式的调整

暗色模式下，玻璃效果需要特别调整以避免「脏」感：

| 参数 | 亮色 | 暗色 | 原因 |
|---|---|---|---|
| 模糊半径 | 25dp | 20dp | 暗色下过度模糊显得浑浊 |
| 饱和度倍率 | 1.5x | 1.2x | 暗色下高饱和度显得荧光 |
| 亮度偏移 | +5% | -3% | 暗色下需微微压暗 |
| 描边不透明度 | 25%（`#40`） | 12%（`#20`） | 暗色下描边过亮会突兀 |
| 高光不透明度 | 19% | 8% | 暗色下高光只需极微弱提示 |
| 环境色 tint | 3%~5% | 5%~8% | 暗色下需略加强 tint 才能感知 |

### 10.3 适配检查清单

每次新增/修改 UI 元素时，必须检查：

- [ ] `values/colors.xml` 中有该颜色定义
- [ ] `values-night/colors.xml` 中有对应暗色值
- [ ] 玻璃组件在暗色下不显得「脏」或「浑浊」
- [ ] 文字在两种模式下对比度满足 WCAG AA（4.5:1）
- [ ] 图标在两种模式下清晰可辨
- [ ] 边缘高光在暗色下不过于突兀
- [ ] 阴影在暗色下不形成黑洞（改用 stroke 补偿）

---

## 11. 实施计划与优先级

### 阶段 S1：基础设施（约 1 周）

| 编号 | 任务 | 产出 |
|---|---|---|
| S1.1 | 创建 `LiquidGlassView` 自定义 View | 核心组件，支持 Tier 1/2/3 三级降级 |
| S1.2 | 添加自定义属性 `attrs_liquid_glass.xml` | XML 中可配置模糊/饱和/圆角等 |
| S1.3 | 统一图标 strokeWidth → 1.5 | 修正 `ic_search`、所有 `ic_action_*` |
| S1.4 | 补充边缘高光颜色 Token | `glass_highlight_top`、`glass_shadow_bottom` 等 |
| S1.5 | 补充暗色模式 Token | 所有新增颜色的 `values-night` 对应 |

### 阶段 S2：核心页面玻璃升级（约 1 周）

| 编号 | 任务 | 产出 |
|---|---|---|
| S2.1 | 首页搜索栏替换为 `LiquidGlassView` | 真实模糊 + 边缘高光 |
| S2.2 | 聊天页输入栏替换为 `LiquidGlassView` | 模糊 + 键盘联动 |
| S2.3 | 聊天页 Toolbar 添加滚动联动 | elevation + 模糊强度随滚动变化 |
| S2.4 | 对话框背景升级为玻璃材质 | Dialog / BottomSheet 模糊背景 |

### 阶段 S3：操作系统重构（约 1 周）

| 编号 | 任务 | 产出 |
|---|---|---|
| S3.1 | 消息操作栏重构（三级分层） | 常驻 + 展开菜单 + 长按 |
| S3.2 | 操作菜单改为玻璃材质弹出 | PopupWindow + LiquidGlassView |
| S3.3 | 会话列表添加滑动操作 | 左滑露出置顶/隐藏/删除 |
| S3.4 | 聊天页 Toolbar 精简 | 减少按钮 + 更多菜单 |

### 阶段 S4：功能丰富 + 动态响应（约 1~2 周）

| 编号 | 任务 | 产出 |
|---|---|---|
| S4.1 | 引用回复功能 | 长按引用 + 输入框预览 |
| S4.2 | 消息搜索功能 | Toolbar 搜索 + 高亮结果 |
| S4.3 | 会话置顶功能 | 置顶逻辑 + 列表排序 |
| S4.4 | 触摸反馈 + 动画打磨 | 按压缩放、过渡动画 |
| S4.5 | 环境色 tint（静态方案） | 基于主题色的玻璃 tint |

### 阶段 S5：高级特性（选做）

| 编号 | 任务 | 产出 |
|---|---|---|
| S5.1 | 环境色 tint（动态采样方案） | 实时背景色采样 |
| S5.2 | TTS 朗读功能 | 助手消息朗读 |
| S5.3 | 多选模式 | 批量操作 |
| S5.4 | 语音输入 | 语音转文字 |
| S5.5 | 图片/文件附件 | 多模态支持 |

---

## 12. 验收标准

### 12.1 视觉验收

| 标准 | 验证方法 |
|---|---|
| 玻璃组件可以「透视」背景内容 | 在彩色背景上放置玻璃 Toolbar，确认背景色可见 |
| 上边缘亮、下边缘暗 | 截图对比上下边缘亮度差异 |
| 滚动时有模糊强度变化 | 快速滚动时观察 Toolbar 模糊是否变化 |
| 暗色模式下玻璃不「脏」 | 暗色模式截图检查是否浑浊 |
| 图标视觉粗细一致 | 并排截图所有图标，检查线条粗细 |
| 阴影不生硬 | 截图对比阴影是否柔和 |

### 12.2 性能验收

| 标准 | 阈值 |
|---|---|
| 聊天页滚动帧率 | ≥ 55 fps（Tier 1），≥ 50 fps（Tier 2） |
| 模糊渲染耗时 | ≤ 5ms per frame |
| 内存增量 | ≤ 30MB（模糊 Bitmap 缓存） |
| API 26 设备无崩溃 | Tier 3 降级正常显示 |
| 电池影响 | 非滚动状态不持续渲染 |

### 12.3 交互验收

| 标准 | 验证方法 |
|---|---|
| 常驻操作 ≤ 3 个 | 数每条消息下方的按钮数量 |
| 删除操作需二次确认 | 点击删除后必须出现确认 |
| 滑动操作可取消 | 滑动中松手回弹 |
| 操作菜单出现有动画 | 200ms fade+slide |
| Toolbar 搜索可用 | 输入关键词能高亮定位 |

---

*创建于 2026-04-15 — 基于 UI_REDESIGN_PLAN.md 的升级版本*
*适用于 chatbox-android dev 分支*
