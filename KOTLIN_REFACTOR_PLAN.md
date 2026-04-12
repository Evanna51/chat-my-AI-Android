# Java → Kotlin 重构计划

**分支**: `refactor/java-to-kotlin`（从 `dev` 拉出）  
**范围**: 74 个 Java 文件 → Kotlin  
**约束**: 增量迁移，每阶段完成后必须 `./gradlew assembleDebug` 通过

---

## 阶段顺序与依赖

```
SA-1 (build.gradle)
  └── SA-2 (Domain Models)
        └── SA-3 (Room Entities)
              └── SA-4 (DAOs + AppDatabase)
                    ├── SA-5 (工具类/Config)  ─┐
                    ├── SA-6 (Store 层)       ─┤── 并行
                    │                          │
                    ├── SA-7 (MessageAdapter)  ─┤── SA-5/6 完成后并行
                    └── SA-8 (其余 Adapters)  ─┘
                          ├── SA-9 (ChatService)
                          ├── SA-10 (Service/ViewModel)
                          └── SA-11/12 (Activities)
```

---

## SA-1 — Pre-setup: build.gradle 配置

**文件**: `build.gradle` (root), `app/build.gradle`

**需要做**:
- Root: 添加 `id 'org.jetbrains.kotlin.android' version '1.9.25' apply false`
- App: 添加 `id 'org.jetbrains.kotlin.android'` + `id 'org.jetbrains.kotlin.kapt'`
- App: 添加 `kotlinOptions { jvmTarget = '17' }` 到 `android {}` 块
- App: 将 `annotationProcessor 'androidx.room:room-compiler:2.6.1'` 改为 `kapt 'androidx.room:room-compiler:2.6.1'`
- App: 添加 `implementation 'androidx.core:core-ktx:1.12.0'`
- **验证**: `./gradlew assembleDebug` 在 0 个 kt 文件时编译通过

---

## SA-2 — Phase 1: Domain Model (7 文件)

**文件**: SessionMeta, SessionOutlineItem, SessionChatOptions, MyAssistant, SessionSummary, ProviderInfo, CharacterMemoryApi

**关键 Kotlin 改造**:
- `SessionMeta`, `SessionOutlineItem`, `SessionChatOptions`, `MyAssistant` → `data class`（所有字段有默认值，保证无参构造）
- `SessionSummary` → 普通 `class`（Room 查询投影类型，需保留无参构造 + `@Ignore` 字段）
- `ProviderInfo.createCustom()` + 内部 `ProviderModelInfo` → `companion object` + 嵌套 `data class`
- `CharacterMemoryApi` → `object`（单例），内部请求/响应类 → 嵌套 `data class`

**验证**: `./gradlew assembleDebug`

---

## SA-3 — Phase 2: Room Entity (5 文件)

**文件**: Message, SessionMetaEntity, SessionChatOptionsEntity, MyAssistantEntity, SessionAssistantBindingEntity

**关键 Kotlin 改造**:
- 所有 Entity 保持普通 `class`（Room 反射需无参构造，不用 data class）
- `Message.ROLE_USER` / `ROLE_ASSISTANT` → `companion object` + `@JvmField`（Java 互调期间保持访问）
- `@NonNull @PrimaryKey` → Kotlin 非空类型（去掉 `@NonNull`）
- `@Ignore` 注解的字段/构造器保持 `@Ignore`
- `Boolean` 字段保持 `var`, 不可为 null

**风险**: `Message` 被 `MessageAdapter` 用作 IdentityHashMap key，**不能** 成为 data class（不可覆盖 equals/hashCode）

**验证**: `./gradlew assembleDebug` + 检查 `build/generated/source/kapt/debug/` 生成了正确的 Room `_Impl`

---

## SA-4 — Phase 3: DAO + AppDatabase (6 文件)

**文件**: MessageDao, SessionMetaDao, SessionChatOptionsDao, MyAssistantDao, SessionAssistantBindingDao, AppDatabase

**关键 Kotlin 改造**:
- DAO 从 `interface` 保持 `interface`，注解不变
- 可能返回 null 的 DAO 方法（如 `getAssistantId()`）必须标 `String?`
- `AppDatabase.INSTANCE` → `companion object { @Volatile private var INSTANCE: AppDatabase? = null; @JvmStatic fun getInstance(ctx: Context) = ... }`
- `MIGRATION_1_2` → `companion object` 中的 `val`，匿名 `object : Migration(1, 2)`

**验证**: `./gradlew assembleDebug`

---

## SA-5 — Phase 4A: 工具类/Config (9 文件)

**文件**: ApiUtils, ConfigManager, ModelConfig, AiModelConfig, ThemeSettingsHelper, AssistantAvatarHelper, ExportUtil, FormInputScrollHelper, ProviderRequestOptionsBuilder

**关键 Kotlin 改造**:
- `final class` + `private constructor()` + 全静态方法 → Kotlin `object`（ApiUtils, ThemeSettingsHelper, AssistantAvatarHelper, ProviderRequestOptionsBuilder）
- `prefs.edit().putX(k,v).apply()` → `prefs.edit { putX(k,v) }`（需 core-ktx）
- `AiModelConfig.ResolvedConfig` 内部类 → 嵌套 `data class`

**验证**: `./gradlew assembleDebug`

---

## SA-6 — Phase 4B: Store 层 + 基础设施 (13 文件)

**文件**: SessionMetaStore, SessionChatOptionsStore, MyAssistantStore, SessionAssistantBindingStore, SessionOutlineStore, ProactiveMessageSyncStore, CharacterMemoryConfigStore, RoomMigrationHelper, ProviderManager, ProviderCatalog, ConfiguredModelPicker, CharacterMemoryService, ModelsFetcher

**关键 Kotlin 改造**:
- `for (Map.Entry<K,V> e : map.entrySet())` → `for ((k, v) in map)`
- `v != null && !v.isEmpty()` → `!v.isNullOrEmpty()`
- `ProviderManager` Gson TypeToken: `new TypeToken<Map<...>>(){}.getType()` → `object : TypeToken<Map<...>>(){}.type`
- `ProviderCatalog` 静态初始化块 → `companion object { init { CATALOG.add(...) } }`
- `ProviderSettings` 保持普通 `class`（Gson 反射实例化，需无参构造 + nullable 字段）

**验证**: `./gradlew assembleDebug` + 手动测试：设置页添加 Provider、配置模型

---

## SA-7 — Phase 5A: MessageAdapter (1 文件，高复杂度)

**文件**: MessageAdapter.java（~700 行）

**关键 Kotlin 改造**:
- `AssistantHolder.textCollapseToggle` 已是 `ImageView`（最近重构）
- `instanceof` → `is` + 智能类型转换
- `Collections.newSetFromMap(new IdentityHashMap<>())` 保留（reference equality 语义）
- `interface OnMessageActionListener` 保持 `interface`（多方法，无法 `fun interface`）
- nested state store classes → 嵌套 `class`（非 data class）

**风险**: `Message` 作为 IdentityHashMap key，必须保持 reference equality（SA-3 已注明）

**验证**: `./gradlew assembleDebug` + 真机运行聊天界面验证消息渲染/折叠/Markdown

---

## SA-8 — Phase 5B: 其余 Adapters (10 文件)

**文件**: SessionListAdapter, SessionOutlineAdapter, HomeAssistantAdapter, MyAssistantListAdapter, AllConversationsAdapter, AddedModelAdapter, AvailableModelAdapter, ModelListAdapter, ModelPickerAdapter, ProviderCatalogAdapter, ProviderListAdapter

**关键 Kotlin 改造**:
- ViewHolder 构造器 → `init {}` 块做 `findViewById`
- 匿名 `Comparator` / `Runnable` → trailing lambda
- `interface` 监听器保持 `interface`

**验证**: `./gradlew assembleDebug` + 打开主界面、助手列表、Provider 列表

---

## SA-9 — Phase 6A: ChatService (1 文件，最高复杂度)

**文件**: ChatService.java（~1800 行）

**关键 Kotlin 改造**:
- `ChatHandleImpl` → `inner class`（需访问外部类）
- `InlineThinkState`, `ContentReasoningParts` → private inner class
- `@Volatile` 字段 → `@Volatile var`
- `call.enqueue(new retrofit2.Callback<T>() {...})` → `call.enqueue(object : retrofit2.Callback<T> { ... })`
- `ChatCallback` 多方法接口保持 `interface`（不能 fun interface）
- 公共方法签名不变（ChatViewModel 调用它）

**验证**: `./gradlew assembleDebug` + 真机发送消息 + 中途取消流式输出

---

## SA-10 — Phase 6B: 其余 Service/ViewModel (6 文件)

**文件**: ChatApi, ChatViewModel, CharacterMemoryService, ProactiveMessageSyncManager, ProactiveMessageNotifier, ProactiveMessageWorker

**关键 Kotlin 改造**:
- `ChatApi` 嵌套请求/响应类 → `data class`（ChatMessage, ChatResponse 等）
- `ProactiveMessageSyncManager.SyncCallback` → `fun interface`（单抽象方法，启用 SAM 转换）
- `ChatViewModel.StreamDeltaEvent` 保持普通 `class`（字段在构造后赋值）

**验证**: `./gradlew assembleDebug` + 验证 WorkManager 定时任务调度

---

## SA-11 — Phase 7A: 简单 Activity + Application (9 文件)

**文件**: AIChatApp, ThemedActivity, SettingsActivity, GeneralSettingsActivity, ModelConfigActivity, SessionChatSettingsActivity, ConfigActivity, AllConversationsActivity, SessionOutlineActivity

**关键 Kotlin 改造**:
- `extends ThemedActivity` → `: ThemedActivity()`
- `@Override protected void onCreate(Bundle b)` → `override fun onCreate(savedInstanceState: Bundle?)`
- 常量 → `companion object`
- `ThemedActivity.attachBaseContext(Context?)` 注意 nullable 参数

**验证**: `./gradlew assembleDebug` + 打开设置页、对话列表、大纲

---

## SA-12 — Phase 7B: 核心 Activity (5 文件，高复杂度)

**文件**: MainActivity, ChatSessionActivity (~2500 行), EditMyAssistantActivity, MyAssistantsActivity, ProviderDetailActivity, ChatSettingsFormModule

**关键 Kotlin 改造**:
- `ChatSessionActivity` Handler/Runnable 链 → `mainHandler.post { ... }`（SAM 转换）
- `intent.getStringExtra(KEY) ?: UUID.randomUUID().toString()`
- `ChatSettingsFormModule` → 普通 Kotlin class（Activity 级模块）

**验证**: `./gradlew assembleDebug` + 完整端到端冒烟测试：
1. 首次安装打开 App
2. 设置中添加 Provider
3. 开新对话、发消息、看到流式回复
4. 主题切换验证（ThemedActivity.onResume）

---

## 通用注意事项

| 风险 | 处理方式 |
|------|---------|
| Java null 返回 | 所有方法返回类型加 `?`，调用处用 `?: ""` |
| Room Entity 无参构造 | 用 `var` + 默认值，不用 data class |
| IdentityHashMap key | `Message` 不能是 data class（保持 reference equality）|
| Java 互调期间静态成员 | `companion object` + `@JvmStatic` / `@JvmField` |
| Gson 反射类 | 保持普通 class + 所有字段 nullable + 默认值 |
| Kotlin inner class | 内部需访问外部类的类用 `inner class` |
