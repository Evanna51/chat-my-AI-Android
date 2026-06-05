# 自动对话消息协议 — AI 编码前必读

> 每次触碰 **删除 / 同步 / 持久化 / DB 查询 / proactiveKind** 相关代码前，
> 必须读完本文。这里记录的逻辑不会在代码注释里重复，踩了就是线上数据问题。

---

## 1. proactiveKind 枚举含义

| 值 | 含义 | 来源 | server 知道吗 | synced 初值 |
|---|---|---|---|---|
| `0` | 普通用户消息 / AI 正常回复 | 用户输入 / ChatService | **是** | 0（drainer 负责同步） |
| `1` | split 分段 | `ProactiveChatPlanner.applySplit()` 拆出 | **否**（客户端独有） | 1（永远不推送） |
| `2` | follow-up / 远程 proactive | `ProactiveFollowUpWorker` / `WsClient.persistAndNotify()` | **是** | 1（来自 server 或已推过） |

**核心规则：proactiveKind != 0 的行，drainer 和 persistSessionMessagesAsync 全部跳过，不参与与 server 的对账。**

---

## 2. split 消息的完整生命周期

```
ChatViewModel.sendMessage()
  │
  ├─ insert Message(consolidated_content, proactiveKind=0, synced=0)
  │    └─ WsClient.sendMessageCreate(turnId, consolidated_content)
  │         └─ server 存入 consolidated_content（这是 server 唯一知道的内容）
  │
  ├─ server 回 message_persisted → markSynced(turnId) → synced=1
  │
  └─ ProactiveChatPlanner.applySplit(insertedMessageId, split=[s0, s1, s2])
       ├─ updateContentAndProactiveKind(insertedMessageId, s0, proactiveKind=1)
       │    ↑ 只改本地 DB，【不调 sendMessageUpdate】
       │    ↑ server 永久保有 consolidated_content，客户端显示 s0（内容分歧！）
       ├─ postDelayed: insert Message(s1, proactiveKind=1, synced=1, 本地 turnId)
       └─ postDelayed: insert Message(s2, proactiveKind=1, synced=1, 本地 turnId)
```

### 已知问题（暂未修复，编码时别踩）

| # | 问题 | 影响范围 | 当前状态 |
|---|---|---|---|
| A | split[0] 内容与 server 永久分歧 | server `search_memory` 召回内容是 consolidated 版 | 已知，暂接受 |
| B | 删 split[0] 不级联删 split[1..N] | split[1..N] 变孤儿消息留在 DB | **未修复，见第 3 节** |
| C | 删 split[1..N] 会发 WS delete for 未知 turnId | server 静默忽略，客户端日志有噪音 | 已知，harmless |

---

## 3. 消息删除规则

### 3.1 普通消息（proactiveKind=0）
`deleteMessageAsync(message)` 的完整路径：
1. `db.messageDao().deleteById(msgId)` — 删本地 DB
2. `WsClient.sendMessageDelete(turnId, assistantId)` — 通知 server 删 turn + embedding

### 3.2 split 消息删除（proactiveKind=1）

**删 split[0]（保留 server turnId 的那条）**
- DB 删 ✓
- WS delete 发送 ✓（server 认识这个 turnId，会删）
- **split[1..N] 仍留在 DB！** — persistSessionMessagesAsync 不管 proactiveKind!=0 的行
- 处理方法：删 split[0] 时，**必须额外查出同会话内紧跟的 split[1..N]（按 createdAt 范围或 turnId 关联）一并删除**

**删 split[1..N]（本地生成 turnId 的那些）**
- DB 删 ✓
- WS delete 发送（server 不认识此 turnId → server 404 → client 静默忽略）
- 建议：发送前判断 `msg.synced == 0 || isServerKnownTurnId(turnId)` 再决定是否发 WS delete
- 短期兜底：`sendMessageDelete` 内部可以加 guard：server turnId 是 UuidV7 格式，如果是本地生成的跳过 WS 调用（但需要先建立区分机制）

### 3.3 follow-up 消息（proactiveKind=2）

**来自 WsClient 的远程 proactive（server 发推来的）**
- turnId = server 端生成的稳定 id（已写回 msg.turnId）
- 删时 WS delete 有效，server 会删 turn + embedding

**来自 ProactiveFollowUpWorker 的本地生成 follow-up**
- turnId 是本地 UuidV7，但内容已通过 WS `message_create` 推给 server（proactiveKind=2 且 synced=1 但是否真的推了要看 WS 是否在线）
- 目前 synced=1 是硬写的，未必真的到了 server

---

## 4. 同步规则速查

| 场景 | drainer 处理 | persistSessionMessagesAsync | WS 实时 |
|---|---|---|---|
| proactiveKind=0, synced=0 | **入队推送** | 参与对账 | sendMessageCreate |
| proactiveKind=0, synced=1 | 跳过 | 参与对账（只 update content，不改 synced） | — |
| proactiveKind=1（split） | **永远跳过** | **永远跳过** | 无 |
| proactiveKind=2（follow-up） | **永远跳过** | **永远跳过** | 无（已推或不推） |

**persistSessionMessagesAsync 的边界：只管 `role IN (0,1) AND proactiveKind=0` 的行。**
修改这个查询条件时极度小心，错误包含 proactiveKind!=0 会把 split/follow-up 当普通消息对账，
可能触发错误的 deleteById 或内容覆写。

---

## 5. DB 查询注意事项

凡是写"查某个 session 的所有消息"相关 SQL，必须确认是否需要包含 proactiveKind!=0：

```sql
-- 用户可见的消息（含 split/follow-up）
SELECT * FROM message WHERE sessionId = ? ORDER BY createdAt ASC

-- 仅参与 API context 的消息（不含 split，因为 split 内容是展示用的，不是真实对话轮次）
SELECT * FROM message WHERE sessionId = ? AND proactiveKind = 0 AND role IN (0,1)
ORDER BY createdAt ASC

-- 待同步队列（drainer）
SELECT * FROM message WHERE synced = 0 AND proactiveKind = 0 AND syncAttempts < ?
```

**特别注意**：构建发给模型的 `historyForApi` 时，如果把 split 行（proactiveKind=1）
包含进去，同一条 AI 回复会变成 3 条 assistant 消息发给 API，context 会失真。

---

## 6. 待做事项（按优先级）

1. **P1 — split[0] 删除时级联删 split[1..N]**
   - `deleteMessageAsync` 检测 `message.proactiveKind == 1` 且是 split[0]（如何判断：
     它的 turnId 是 server 知道的那条），查出 createdAt 之后紧跟的 proactiveKind=1 行一并删
   - 或：insert split[1..N] 时写入 `parentTurnId = split[0].turnId`（需加 DB 字段）

2. **P2 — split[0] 内容更新通知 server**
   - `applySplit()` 改写 split[0] 后，调 `WsClient.sendMessageUpdate(turnId, split[0], assistantId)`
   - 让 server `search_memory` 和 re-embed 用的是分段后的内容

3. **P3 — WS delete guard for 纯本地 turnId**
   - split[1..N] 的 turnId 是本地 UuidV7，server 从未见过
   - `relayDeleteToWs` 加判断：如果 `msg.synced == 1 && msg.proactiveKind == 1` 且不是 split[0]，跳过 WS 调用
