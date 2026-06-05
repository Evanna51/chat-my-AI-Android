# WS 后台耗电优化

## 背景

实测：CPU 总用时 7 min、前台 5 min，但耗电 12%（同设备同时段微信整晚 ~2%）。
CPU 时间不高 + 耗电高 = 典型的**无线电烧电**特征：蜂窝网络下 baseband 反复 RRC
唤醒 + tail energy（每次发包后基带保持 5–30s 唤醒态再睡）。

WS 是后台唯一常驻网络组件（`SyncDrainWorker` 1 天周期 + 入网触发；
`ProactiveFollowUpWorker` 是 OneTimeWork；没有 WakeLock / 周期 alarm），所以耗电
大头就在它身上。

---

## P0-1：协议层 ping 替代应用层 ping ✅ 已完成

**改动**：删除 `WsClient.pingRunnable` + `startPing/stopPing` + 主线程 Handler 唤醒，
改用 OkHttp `pingInterval(4, TimeUnit.MINUTES)` 内置 WebSocket Ping frame。

**收益**：
- 心跳包大小：~40 字节（`{"op":"ping","ts":...}`）→ 2 字节（control frame）
- 不再唤主线程 Handler，主线程能更长时间 idle
- 协议层 ping 由 OkHttp IO 线程处理，路径更短

**server 兼容性**：服务端只需正常响应 WS 协议层 Ping/Pong（RFC 6455），所有主流
WS server 框架默认支持。`op: ping/pong` 应用层处理代码保留（兼容 server 主动
发的 keepalive）。

---

## P0-2：屏幕 + 网络感知主动 shutdown WS（待 server 支持）

### 目标

蜂窝网络下，屏幕灭超过 30 分钟无消息 → 主动 `WsClient.shutdown()`。
靠 `SyncDrainWorker` 周期 / 入网触发时把 server 端积压的 proactive 消息一并拉回。

WiFi 下不动（WiFi 心跳几乎不耗电）。

### 决策矩阵

| 屏幕 | 网络 | 行为 |
|---|---|---|
| 开 | * | WS 维持长连 |
| 灭 | WiFi | WS 维持长连 |
| 灭 | 蜂窝 | 灭屏 30min 后 `shutdown()` |
| 灭→开 | * | 立即 `start()` 重连 |
| 切到蜂窝 + 屏幕灭 | — | 立即触发 30min 倒计时 |

### 实施要点

1. 新建 `WsPowerPolicy`（或直接挂在 `WsClient` 内）
2. 监听 `Intent.ACTION_SCREEN_ON / OFF`（必须运行时 register，manifest 声明在
   Android 7+ 已不生效）
3. `ConnectivityManager.NetworkCallback` 拿当前 transport（已经有
   `SyncScheduler.registerDefaultNetworkCallback`，可以复用同一个 callback 分发）
4. 30min 倒计时用 `mainHandler.postDelayed`（不需要穿透 Doze，反正 Doze 下 WS
   也死）

### **server 端待办（决定能不能上 P0-2）**

需要新增/确认接口：**按 assistantId 拉取一段时间内未推送的 proactive**

```
GET /api/proactive/pending?assistantId=...&since=...&limit=...
→ [{ id, assistantId, sessionId, title, body, createdAt }, ...]
```

或者扩展现有 drain 接口，让它一并返回积压的 proactive。

没有这个接口的话，shutdown WS 期间产生的 proactive 消息会丢（server 端会把它们
入队等 WS 重连推送，但客户端长时间不连就堆积；当前 WS 重连后 server 会发
`queued_batch`，所以严格说也不算丢，但延迟可能跨小时级）。

**核实方式**：去 `wi-chat-server` worktree 看 `/api/ws` 的 `queued_batch` 推送
逻辑，确认 server 端有持久化队列且能在重连时下发。如果有，P0-2 不需要新接口，
直接做即可（重连时 server 会把积压一次推过来）。

---

## P1：屏幕状态感知 ping 间隔（可选）

OkHttp `pingInterval` 是构造期参数，运行期改不了。如果要做动态间隔：
- 屏幕开 → `pingInterval(4 min)` 客户端
- 屏幕灭 → `pingInterval(10 min)` 客户端

需要在屏幕状态变化时**重建 OkHttpClient + 重连 WS**，代价不小。建议 P0-2 做完
观察实际收益再决定。

---

## P2：主动检测 Doze 模式

`PowerManager.isDeviceIdleMode` 检测到进入 Doze → 主动 `shutdown()`，避免 OkHttp
在 Doze 下不停尝试发 ping → onFailure → scheduleReconnect → 又被冻 的死循环
（虽然每次都被系统冻住所以耗电不一定多，但日志会很难看）。

退出 Doze（`ACTION_DEVICE_IDLE_MODE_CHANGED`）→ `start()`。

P0-2 做完后基本上就无所谓了，因为蜂窝场景已经主动 shutdown，剩下 WiFi 场景
Doze 也会冻 socket，影响有限。

---

## 验证方法

每次改动后，连 USB → `adb shell dumpsys batterystats --reset` → 锁屏 一晚 →
`adb shell dumpsys batterystats > before.txt`。重点看：
- `Cellular radio total time` / `Wifi total time`
- `Mobile network: ... rx/tx packets`
- 进程级 `Cpu: ... u + ... s`

目标：一晚锁屏耗电 < 3%（接近微信水平）。
