# docs/ — chatbox-android 文档目录

> AI 助手 / 新人请先看 [AI_GUIDE.md](AI_GUIDE.md)。

---

## 一句话导航

| 你要… | 看 |
|---|---|
| **快速上手项目，知道改 X 去哪里** | [AI_GUIDE.md](AI_GUIDE.md) ← 第一份要读 |
| **包结构 / 谁依赖谁** | [architecture/MODULE_MAP.md](architecture/MODULE_MAP.md) |
| **理解一条用户消息的完整链路** | [features/CHAT_FLOW.md](features/CHAT_FLOW.md) |
| **改流式 / 打字机 / reasoning / 工具调用** | [features/STREAMING.md](features/STREAMING.md) |
| **加新的 assistant.type** | [features/SESSION_MODES.md](features/SESSION_MODES.md) |
| **故事模式 metaJson schema / Story Tools** | [features/STORY_TYPES.md](features/STORY_TYPES.md) |
| **改作家模式 / 大纲 / 章纲** | [features/WRITER_MODE.md](features/WRITER_MODE.md) |
| **改角色模式 / 情绪 / TTS** | [features/CHARACTER_MODE.md](features/CHARACTER_MODE.md) |
| **改远程同步 / 工具 / WS** | [features/SYNC.md](features/SYNC.md) |
| **Proactive 自动对话协议** | [features/proactive-message-protocol.md](features/proactive-message-protocol.md) |
| **Split 消息的设计** | [features/split-message-design-review.md](features/split-message-design-review.md) |
| **WS 电池/网络优化** | [features/ws-battery-optimization.md](features/ws-battery-optimization.md) |
| **UI 风格 / 主题** | [ui/UI_REDESIGN_PLAN.md](ui/UI_REDESIGN_PLAN.md) + [ui/STYLE_OPTIMIZATION_MANUAL.md](ui/STYLE_OPTIMIZATION_MANUAL.md) |
| **历史 refactor 决策（已完成）** | [archive/refactor/](archive/refactor/) |
| **历史功能 progress doc（已完成）** | [archive/features/](archive/features/) |

---

## 文档维护原则

1. **AI_GUIDE 是主索引**：任何新加的文档都要在 AI_GUIDE §5 加一条链接
2. **每份 feature 文档结构相同**：
   - 触发条件 / 关键文件锚点
   - 数据流 / 状态
   - 常见陷阱
   - "加新功能去哪改"映射表
3. **过期文档移 archive/**（不删，保留决策历史）
4. **不写实现细节**（细节看代码 KDoc），文档写「为什么这样」+「去哪里改」

---

## 当前重构进度

R1–R10 完成（commits e649a66...910ced3）。三大胖文件状态：

| 文件 | 起始 | 当前 | 减幅 |
|---|---|---|---|
| ChatService | 2309 | ~870 | -62% |
| ChatSessionActivity | 2281 | ~1940 | -15% |
| MessageAdapter | 1054 | ~940 | -11% |

行为差异已物理隔离到 `session/` strategy + `writer/` service + `adapter/` binder 子文件。改 mode-specific 行为不再需要全局搜 if 分支。

下一步候选：
- **R11** — `SessionOutlineStore` SP→Room 迁移
- **R12** — `AssistantBubbleBinder` 抽取（与 MessageAdapter 状态强耦合，需要先解耦）
- **故事模式 S6 后置项** — wi-server `/api/story/status-history` endpoint（参考 [STORY_TYPES.md](features/STORY_TYPES.md)）
