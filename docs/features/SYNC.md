# 跨端同步 — wi-chat-server / Memory Tool / WS

> 这个 App 与外部 `wi-chat-server`（独立 Node.js 服务）协作。Server 提供：消息持久化、跨端拉取、记忆 tool、proactive 推送。

---

## 1. 架构图

```
[Android Device 1]
    ↕ WebSocket (实时事件 / proactive 推送)
    ↕ HTTPS (上传消息 / 拉历史 / 调 tool)
[wi-chat-server]
    ├─ Postgres (消息 + memory + assistants)
    ├─ Tavily search proxy
    └─ proactive 调度器
    ↕
[Android Device 2 / Web 端]
```

Android 客户端是「最终一致性」节点：本地 DB 先写，背景上传到 server。

---

## 2. 关键文件

| 文件 | 职责 |
|---|---|
| [`sync/RemoteSyncConfigStore.kt`](../../app/src/main/java/com/example/aichat/sync/RemoteSyncConfigStore.kt) | 远程同步配置（baseUrl / apiKey） |
| [`sync/ChatServerApi.kt`](../../app/src/main/java/com/example/aichat/sync/ChatServerApi.kt) | HTTP 客户端（拉历史 / 拉 context / 上传消息） |
| [`sync/ChatDtos.kt`](../../app/src/main/java/com/example/aichat/sync/ChatDtos.kt) | 数据传输对象 |
| [`sync/WsClient.kt`](../../app/src/main/java/com/example/aichat/sync/WsClient.kt) | WebSocket 客户端（接收 proactive / 服务端 push） |
| [`sync/MemoryToolApi.kt`](../../app/src/main/java/com/example/aichat/sync/MemoryToolApi.kt) | memory tool HTTP 客户端（search_memory / correct_memory / web_search） |
| [`sync/ToolBridge.kt`](../../app/src/main/java/com/example/aichat/sync/ToolBridge.kt) | 工具调用桥接（LLM → tool 名 → 实际 HTTP） |
| [`sync/SyncQueueDrainer.kt`](../../app/src/main/java/com/example/aichat/sync/SyncQueueDrainer.kt) | 待上传消息队列 |
| [`sync/SyncDrainWorker.kt`](../../app/src/main/java/com/example/aichat/sync/SyncDrainWorker.kt) | WorkManager 任务（背景上传） |
| [`sync/SyncScheduler.kt`](../../app/src/main/java/com/example/aichat/sync/SyncScheduler.kt) | 调度策略（WiFi 入网 / 每日 1 次 / 手动） |
| [`sync/HistoryBackfiller.kt`](../../app/src/main/java/com/example/aichat/sync/HistoryBackfiller.kt) | 第一次打开会话时从 server 拉历史回填 |
| [`sync/ChatContextCache.kt`](../../app/src/main/java/com/example/aichat/sync/ChatContextCache.kt) | session context 缓存（角色 bootstrap / 长期记忆摘要） |
| [`sync/SnapshotUploader.kt`](../../app/src/main/java/com/example/aichat/sync/SnapshotUploader.kt) | 整 session snapshot 上传 |
| [`sync/EffectivePromptStore.kt`](../../app/src/main/java/com/example/aichat/sync/EffectivePromptStore.kt) | 调用 LLM 时的"实际拼好的 system prompt"留底（调试用） |
| [`sync/CharacterBootstrapStore.kt`](../../app/src/main/java/com/example/aichat/sync/CharacterBootstrapStore.kt) | 角色初始数据（人设 / 背景） |
| [`sync/SearchMemoryFormatter.kt`](../../app/src/main/java/com/example/aichat/sync/SearchMemoryFormatter.kt) | search_memory 结果转友好文本 |
| [`sync/UuidV7.kt`](../../app/src/main/java/com/example/aichat/sync/UuidV7.kt) | 跨端唯一 ID 生成 |
| [`sync/DeviceIdProvider.kt`](../../app/src/main/java/com/example/aichat/sync/DeviceIdProvider.kt) | 本机 device id |

---

## 3. 同步策略：drain-only

**重要决策**：客户端**不**实时 push 每条消息。改为：

1. **WiFi 入网时** 触发一次 drain
2. **每天 1 次** 兜底 drain
3. **手动按钮** 立即 drain

详见 archive [project_remote_sync_drain_only.md] 决策记录（已废弃文档 但策略仍生效）。

**后果**：server 端的角色 memory 时效 ≤ 24h（不是实时）。proactive 触发要小心：依赖最新 memory 的触发可能用到 stale 数据。

---

## 4. 拉远程上下文（dispatchChatRequestWithRemoteContextIfEnabled）

每次发用户消息前，可选地先拉一次 context：

```
Activity.dispatchChatRequestWithRemoteContextIfEnabled(...)
  if 远程同步未开 || character/writer 模式禁用 → 直接 dispatchChatRequest
  else:
    ChatServerApi.fetchChatContext(sessionId, assistantId, userMessage)
      → 服务端按 cognition router 规则返回 contextHint + memory hits + relationship snapshot
    把 contextHint 拼到 systemPrompt 末尾 → dispatchChatRequest
```

context fetch ≤ 3s 超时；失败 fallback 到 no-context dispatch（不阻断聊天）。

---

## 5. 工具调用桥接

LLM 用工具流程见 [STREAMING.md §5](STREAMING.md)。客户端这边：

```
LLM 流式返回 tool_calls
  ↓ ChatService.streamChat 解析后调
ToolBridge(assistantId, sessionId).execute(toolName, argumentsJson)
  ↓ when (toolName)
search_memory  → MemoryToolApi.memoryRecall(...) 
correct_memory → MemoryToolApi.memoryCorrect(...)
web_search     → MemoryToolApi.webSearch(...)
  ↓ 返回 JSON string（成功或 error 包装）
  → 作为 tool_result message 放进下一轮 streamChat
```

工具 schema 在 [ToolBridge.kt](../../app/src/main/java/com/example/aichat/sync/ToolBridge.kt) 里以 JsonObject 写死（不动态加）。给 LLM 看的 schema 必须和 server 端实际接受的参数一致 → 改一边要改两边。

---

## 6. WebSocket（proactive 入口）

[`WsClient`](../../app/src/main/java/com/example/aichat/sync/WsClient.kt) 长连接 server，接收：
- `proactive_message` — server 主动推送一段"你应该这样回应用户"的指令
- `memory_updated` — 提示客户端某 assistant 的 memory 变了（刷 ChatContextCache）
- 其它运维事件

WS 连接管理：
- App 启动后建立
- 后台时维持（不断开）
- 设备无网 / 服务端不可达 → 指数退避重连
- 电池优化 / Doze mode → 见 [ws-battery-optimization.md](ws-battery-optimization.md)

---

## 7. 持久化字段

`Message` 表里和 sync 有关的字段：
- `id` — 本地 Room rowId（自增）
- `messageUuid` — 跨端 UUID v7（生成器 [UuidV7](../../app/src/main/java/com/example/aichat/sync/UuidV7.kt)）
- `assistantId` — 空字符串表示「本地审计行」（如 tool_call / tool_result），SyncQueueDrainer 跳过不传
- `turnId` — 同一轮对话的所有消息共享（user + assistant + 可能多条 split）。Server 用它做去重 + delete sync
- `proactiveKind` — 0 / 1 / 2（普通 / split / follow-up）
- `createdAt` — 客户端时间戳（与 server clock 可能有 skew，但保留客户端为准）

---

## 8. 常见陷阱

1. **远程同步未开时**所有 sync/ 调用是 no-op。开发时如果看不到 server 端有数据，先检查 [RemoteSyncSettingsActivity](../../app/src/main/java/com/example/aichat/RemoteSyncSettingsActivity.kt) 开关。
2. **WS 连接不稳**：低电量模式 / Doze 会杀连接。文档见 [ws-battery-optimization.md](ws-battery-optimization.md)，对策是降低心跳频率 + JobScheduler 唤醒。
3. **工具调用 schema mismatch**：客户端 ToolBridge 的 schema 和 server 端 endpoint 期望的参数不一致 → tool 返回 error。改 schema 一定要同步 server。
4. **memory tool 配额**：web_search 每 assistant 每日 ~10 次。超出 server 返回 quota_exceeded。LLM 看到这种 error 后会自适应说"无法获取最新信息"。
5. **跨设备 message 顺序**：server 按 `turnId` + `createdAt` 排序。如果两设备时钟差大，message 顺序可能错乱（罕见但发生过）。
6. **session context cache stale**：ChatContextCache TTL 是 5 分钟。强制刷：清缓存或重启 App。
7. **tool_call / tool_result 不上传 server** 是设计 —— 避免上传量爆炸。但 server 端 schema 实际支持这两个 role（since 2026-Q1），后续可改策略。

---

## 9. 加新同步功能去哪改？

| 我想… | 改 |
|---|---|
| 加新工具 | `ToolBridge.kt`（注册 + 派发）+ `MemoryToolApi.kt`（HTTP）+ `ChatViewModel.systemPrompt`（描述用法给 LLM）+ server 端实现 endpoint |
| 改上传策略（如改成实时） | `SyncScheduler.kt`（触发条件）+ `SyncDrainWorker.kt`（背景任务）|
| 加新 WS 事件类型 | `WsClient.kt` 注册 handler + 路由到对应 ViewModel/Activity |
| 改 chat context 拉取频率 | `dispatchChatRequestWithRemoteContextIfEnabled`（Activity） + `ChatContextCache` TTL |
| 加 server 端推送的消息回 UI | `proactiveMessageEvent` LiveData 路径（[CHARACTER_MODE §6](CHARACTER_MODE.md)） |
