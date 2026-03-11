# AI Chat - Android 原生应用

基于 Java 的 Android 原生 AI 聊天应用，支持 OpenAI 兼容 API。

## 功能

- **聊天**：与 AI 进行多轮对话
- **聊天配置**：支持新建对话
- **模型配置**：设置 API 地址、API Key、模型名称
- **记录导出**：将对话导出为 txt 文件到下载目录

## 环境要求

- Android Studio Ladybug (2024.2.1) 或更新版本
- JDK 17
- Android SDK 34
- minSdk 26

## 构建与运行

1. 用 Android Studio 打开 `chatbox-android` 目录
2. 等待 Gradle 同步完成
3. 连接 Android 设备或启动模拟器（需 SDK 26+）
4. 点击 Run 运行应用

命令行构建（需配置 `local.properties` 中的 `sdk.dir`）：

```bash
cd chatbox-android
./gradlew assembleDebug
# APK 输出位置: app/build/outputs/apk/debug/app-debug.apk
```

## 配置说明

首次使用前，请通过菜单 **设置** 配置：

- **API 地址**：默认 `https://api.openai.com/v1`，可改为其他 OpenAI 兼容接口（如 OpenRouter、本地部署等）
- **API Key**：你的 API 密钥
- **模型**：默认 `gpt-3.5-turbo`，可按需修改

## 技术栈

- Java 17
- Material Design Components
- Retrofit + OkHttp（网络请求）
- Room（本地存储）
- ViewBinding
