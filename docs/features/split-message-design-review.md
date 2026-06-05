# Split 消息设计复盘

> 记录时间: 2026-05-16

---

## 一、Split 的目标

模拟真人发短信节奏：AI 角色把一次回复拆成若干短句，逐条推出（2.5 ~ 8s 间隔），而不是一次推一大段。  
目标场景：角色扮演（character 类型 assistant）。非 character / 无 assistant 会话不启用。

---

## 二、现有方案（V8）

```
LLM 输出 "段A\n\n段B\n\n段C||==STOP==||"
          │
          ▼ 流式阶段 ProactiveSplitStreamFilter
      只显示 "段A"，吞掉 \n\n 后面所有内容
          │
          ▼ onSuccess → ProactiveMetaParser.extract
      cleanContent = "段A"
      meta.split   = ["段A","段B","段C"]
          │
          ├── insert Message("段A") id=42, turnId=T0, synced=0, proactiveKind=0
          │
          └── applySplit():
               ├── 立即 rewrite row42 → content="段A", proactiveKind=1
               ├── postDelayed(D1) → insert Message("段B") turnId=T0, synced=1, proactiveKind=1
               └── postDelayed(D1+D2) → insert Message("段C") turnId=T0, synced=1, proactiveKind=1
```

**主要优点**
- 不改服务端协议，纯客户端实现
- split[0] 正常走 sync 推服务端；split[1..N] synced=1 不推，不污染 server 记忆
- 流式阶段只显示第一段，视觉无跳变

---

## 三、已修复的 Bug（2026-05-16）

| # | Bug | 修复方案 |
|---|-----|---------|
| 1 | **proactiveMessageEvent LiveData coalesce**：Activity STOPPED 时多段 split 触发只保留最后一段 | 加 `pendingProactiveEvents` 队列 + drain 模式，与 `pendingStreamEvents` 对称 |
| 2 | **删除不级联**：删 split[0] 留下孤儿行 | split[1..N] 共用 split[0] 的 turnId；删除时 `deleteSplitGroupByTurnId(turnId)` 整组清除 |
| 3 | **WS delete 无效请求**：split[1..N] 各有独立 turnId，删除时给 server 发 404 | 共用 turnId 后，WS delete 只发一次，server 只认识 split[0] 那条，合法 |
| 4 | **cancelPendingProactive 漏掉 split runnables**：发新消息时旧轮次分段仍在 mainHandler 上 | `cancelPendingProactive()` 加入 `planner?.cancelPendingSplits(sid)` |
| 5 | **filter vs parser 边界不一致**：filter 检测 `"\n\n"` 但 parser 用 `\n\s*\n+` | parser 改为 `\n\n+`，两者一致 |
| 6 | **autoChatEnabled=false 时 parser 仍被调用**：普通会话回复含 `\n\n` 时只显示第一段 | `if (autoChatEnabled)` 分支保护，非 autoChat 路径 `rawFinal.trim()` 直通 |

---

## 四、当前方案的剩余问题

### 4.1 \n\n 与自然段落的歧义

LLM 在角色回复里用 `\n\n` 写了正常段落（引用、换行诗句等），parser 会把它切成两条独立消息，不是期望格式。

**缓解思路**：
- 换一个更不自然的分隔符，如 `<<<SPLIT>>>` 或 `||NEXT||`，在 prompt 里约定
- 同时更新 filter 和 parser 识别该自定义标记

### 4.2 延迟时间体感过长

当前：`min(2500 + 80ms*len, 8000)ms` per segment。30 字的段A → 等 4900ms 才显 段B。  
实测中文平均打字速度约 40~60ms/字，2500ms 基础等待已经够"真实"，80ms/字 是在基础上再加，过长。

**建议**：
- 改为 `min(1500 + 50ms*len, 5000)ms`，或直接 clamp 上限 3000ms

### 4.3 Activity 重建（旋转/配置变化）时分段丢失

ViewModel 存活，但新 Activity 绑定到同一个 ViewModel。问题：
- 旧 planner 的 runnables 捕获的 `onMessageAppended` 是旧 ViewModel 的闭包 → postValue 到旧 ViewModel 的 pendingProactiveEvents
- 新 Activity 观察的是同一 ViewModel（没问题，ViewModel 存活），所以其实这条没问题

但 `pendingProactiveEvents` 队列在 ViewModel 里，新 Activity 第一次 observe 触发时会 drain，能拿到所有排队的事件。这是正确的。  
**结论：旋转场景实际不受影响。**

### 4.4 split 动画在打开历史记录时不再触发

用户关闭聊天页面后再打开，loadMessages 从 DB 加载所有行（包括 split[1..N]），一次性全显示，不再有打字动画。这是合理的取舍——离开再回来应该看到完整记录，不需要重演动画。

---

## 五、备选整体设计方案

### 方案 A：当前方案（已修复版）⭐ 推荐继续

**适用**：当前规模，不需要 server 感知 split 细节

**优点**：
- 实现已存在，修复成本低
- server/client 协议简单（server 只存 split[0]）
- 删除/sync 都只针对 split[0]，server 侧逻辑零变更

**缺点**：
- 分段逻辑在客户端，换一个新客户端（如 iOS）需要重新实现一遍
- `\n\n` 歧义问题（见 4.1）

---

### 方案 B：单行存储 + Adapter 多气泡渲染

**核心思路**：不插入多行，把所有分段内容存在一个 Message 行里（例如以 `\x00` 分隔或存 JSON），MessageAdapter 检测到 proactiveKind=1 时把内容拆成多个气泡显示，动画由 Adapter 自己做延迟 reveal。

**优点**：
- 删除：删一行就干净，无孤儿
- Sync：单行，现有机制完全适用
- 无 LiveData coalesce 问题

**缺点**：
- MessageAdapter 需要大改（目前一行 Message → 一个 ViewHolder，改成一行 → 多个 ViewHolder 需要特殊处理 RecyclerView 的 item count）
- 动画逻辑移进 Adapter，复杂度转移而非消失
- 若未来要让用户删除单个分段，需要拆分存储

---

### 方案 C：改用显式 split marker，消除歧义

把 `\n\n` 换成 `||NEXT||`（或其他 LLM 不会自然输出的标记）：

```
段A||NEXT||段B||NEXT||段C||==STOP==||
```

Filter 和 Parser 都改识别 `||NEXT||`，与正常 `\n\n` 段落不再冲突。

**实现成本低**（只改 filter/parser 两处 + prompt），但需要 prompt 工程调试确保 LLM 可靠输出该标记。

---

### 方案 D：Server 感知 split，返回 messages array（长期方向）

Server 直接返回：
```json
{ "split": ["段A", "段B", "段C"], "followUp": {...} }
```

客户端按 split 数组逐条 insert，完全不需要 filter/parser 在流式阶段做处理。

**优点**：多端一致（iOS/Web 共享 server 切分逻辑），消除 `\n\n` 歧义  
**缺点**：需要 server 改造，成本较高；流式体验需要重新设计（流式期间如何显示？）

---

## 六、近期优化建议（低成本）

1. **替换 split 分隔符**（方案 C）：改 prompt + filter + parser，3 处改动，彻底消除歧义
2. **调整延迟参数**：`SPLIT_MIN_INTERVAL_MS 2500→1500`, `SPLIT_PER_CHAR_MS 80→40`, `SPLIT_MAX_INTERVAL_MS 8000→4000`
3. **追加 split 删除的 UI 处理**：当用户删除一条 proactiveKind=1 的消息时，同时从 `allMessages` 里移除其他相同 turnId 的段落（目前 Activity 只 removeAt(idx) 一条）

---

## 七、已修改文件汇总

| 文件 | 改动 |
|------|------|
| `ChatService.kt` | autoChatEnabled=false 时不调用 parser，rawFinal.trim() 直通 |
| `ChatViewModel.kt` | pendingProactiveEvents 队列；deleteMessageAsync 级联删除；cancelPendingProactive 加 cancelSplits；onAssistantTurnFinalized 传 turnId |
| `ProactiveChatPlanner.kt` | onAssistantTurnFinalized/applySplit 加 splitGroupTurnId 参数；split[1..N] 共用 turnId |
| `MessageDao.kt` | 加 deleteSplitGroupByTurnId 查询 |
| `ChatSessionActivity.kt` | proactiveMessageEvent observer 改为 drain 队列 |
| `ProactiveMetaParser.kt` | PARAGRAPH_SEP 改为 `\n\n+` |
