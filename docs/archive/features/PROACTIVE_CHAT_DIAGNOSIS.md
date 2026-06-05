# 主动对话触发困难 — 诊断与优化方案

## 一、现状问题

角色在客户端的主动对话（follow-up）很难触发，表现为：
- 用户与角色对话后，即使用户长时间不回复，角色也很少主动发消息
- 新角色（亲密度低）几乎无法触发主动消息
- 即使触发，实际送达时间远晚于期望（5-20 分钟）

## 二、根因分析

### 2.1 Prompt 层面（最核心）

#### 问题 A：followUp 默认 null 的条件过于宽泛

**位置：** `ProactivePromptBuilder.buildSystemSuffix()`

当前的硬约束：
```
followUp 默认 null. 满足任一就 null:
  没问问题 / 对方冷淡或单字 / 距上次<60秒 / 已连发≥2条没回 / 当前对话已收尾.
```

**问题：** "没问问题"这一条过于严格。角色扮演场景中，助手回复大量是叙述/行为/情绪反应型（不含问号），但角色仍然可能有主动发消息的意愿（分享日常、关心对方、继续话题等）。这导致绝大多数回合的 followUp 都被模型设为 null。

#### 问题 B：follow-up instruction 的 SKIP 倾向过强

**位置：** `ProactivePromptBuilder.buildFollowUpInstruction()`

```
提醒: 沉默 600s+ / 连续冷淡 / 没"非说不可"的内容 → 倾向 SKIP.
```

600 秒（10 分钟）对角色对话场景来说太短。用户 10-30 分钟不回很正常（忙、在做别的事），角色主动问候/继续话题完全合理。当前 prompt 让模型过度倾向 SKIP。

#### 问题 C：低亲密度的"死循环"

**位置：** `ProactivePromptBuilder.closenessModulationLine()` / `closenessFollowUpLine()`

```kotlin
closeness < 40 → "[亲密度仅 $closeness, 关系尚浅; 慎重打扰, 倾向 SKIP."
```

新角色亲密度起步低（可能 0-20），这个 prompt 几乎完全阻止了主动消息，而亲密度需要互动才能提升，形成恶性循环：
- 不主动 → 没互动 → 亲密度上不去 → 更不主动

### 2.2 调度层面

#### 问题 D：afterSec 建议范围偏大

模型在 META 中输出的 afterSec 常为 300-1200 秒（5-20 分钟）。加上 WorkManager 本身的调度延迟（可能额外 30s-5min，取决于 Doze 模式），实际触发时间远超预期。

**对比预期体验：** 即时通讯场景中"对方正在输入"通常出现在 30-120 秒内，角色主动发消息也应该在 1-5 分钟内到达才有"活人感"。

#### 问题 E：cancel-and-replace 策略断链

**位置：** `ProactiveChatPlanner.onAssistantTurnFinalized()`

```kotlin
cancelFollowUp(sessionId)  // 取消之前的 follow-up
cancelPendingSplits(sessionId)
```

用户每发一条消息，之前排好的 follow-up 就被取消。若用户最后一条消息的模型回复恰好是 `followUp: null`（没问问题、判断对话结束等），整条 follow-up 链就永久断裂。

### 2.3 预算层面

#### 问题 F：split 和 follow-up 共享同一预算

每条 split 段都消耗 `ProactiveBudget.consumeIfAllowed()`，一次多段 split 回复可能消耗 2-3 个配额，挤占 follow-up 的空间。默认 60 条/天看似够用，但 split 消耗会导致实际 follow-up 预算不足。

## 三、优化方案

### 3.1 Prompt 优化（优先级 P0）

#### A. 放宽 followUp 触发条件

**改前：**
```
followUp 默认 null. 满足任一就 null: 没问问题 / 对方冷淡或单字 / ...
```

**改后：**
```
followUp 默认视情况. 以下场景设 null:
  对方冷淡或单字回应 / 已连发≥3条没回 / 刚发过≤30秒
  其他场景（包括没提问但有话想说、关心对方、分享日常、继续话题）均可设 followUp.
  重点: 角色有主动联系对方的动机时就应该设 followUp, 不要只盯着"有没有问号".
```

#### B. 调整 follow-up SKIP 阈值

**改前：**
```
提醒: 沉默 600s+ / 连续冷淡 / 没"非说不可"的内容 → 倾向 SKIP.
```

**改后：**
```
提醒: 连续冷淡 / 已连发≥3条都没回 → 倾向 SKIP.
沉默时长本身不是 SKIP 的理由 — 人会忙、会晚回; 角色在合理时间主动说话是正常的.
```

#### C. 修复低亲密度死循环

**改前：**
```kotlin
closeness < 40 → "关系尚浅; 慎重打扰, 倾向 SKIP."
```

**改后：**
```kotlin
closeness < 40 → "关系还在建立中, 主动消息的语气要自然友好, 不要过于亲热或冒犯; 但不要因此不敢主动."
```

### 3.2 调度优化（优先级 P1）

#### D. 收窄 afterSec 建议范围 + prompt 引导

在 buildSystemSuffix 中调整 afterSec 的指导：
```
afterSec 60..600; 角色关心对方时取较短值(60-180), 话题自然收尾时取较长值(300-600).
```

同时在 ProactiveFollowUp 的 afterSec clamp 调整为 `30..600`（从 30..1800）。

#### E. 默认 follow-up 兜底

当模型回复的 META 中 `followUp == null` 且 `autoStop == false` 时，如果角色有 `allowProactiveMessage = true`，自动注入一个低优先级的默认 follow-up（afterSec=300, intent="关心对方近况"）。这样即使模型没主动提出追问，角色仍有一个兜底的主动窗口。

### 3.3 预算优化（优先级 P2）

#### F. split 不消耗 follow-up 预算

split 是对同一条回复的拆分显示，不应与真正的主动消息共享预算。改为 split 有独立的每回合上限（固定最多 3 段），不走 `consumeIfAllowed()`。

## 四、预期效果

| 指标 | 改前 | 改后 |
|------|------|------|
| 新角色首日 follow-up 触发率 | ~5% | ~40% |
| 成熟角色(亲密度>60) follow-up 触发率 | ~30% | ~70% |
| 平均首次 follow-up 送达时间 | 8-20 分钟 | 2-5 分钟 |
| 日均有效 follow-up 条数 | 1-3 条 | 5-15 条 |
| SKIP 率 | >80% | ~40% |

## 五、风险与对策

| 风险 | 对策 |
|------|------|
| 主动消息过于频繁，用户反感 | 保留每日预算机制（默认 60）+ autoStop 硬刹车 + "已连发≥3条没回" 条件 |
| 低亲密度角色语气不当 | prompt 中保留"语气自然友好，不要过于亲热"的约束 |
| WorkManager 调度延迟不可控 | afterSec 收窄到 60-600 减少累加效应；可考虑 AlarmManager 替代方案（后续） |
