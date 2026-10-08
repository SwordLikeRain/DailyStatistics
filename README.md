# phone-usage-stats

用于测试 Android UsageStats API 的最小 App：读取并显示「今天」各 App 的前台使用时长。

## 功能

- 检查「使用情况访问权限」，未授权时引导到系统设置页
- 读取今天 00:00:00 ~ 当前时间（系统当前时区）的 UsageStats
- 按 `totalTimeInForeground` 从高到低列出各 App（名称、时长、packageName）
- 一键刷新

## 技术要点

- Kotlin + Android Gradle Plugin
- minSdk 23 / targetSdk 35 / compileSdk 35
- 无网络权限、无第三方运行时依赖（仅 AndroidX AppCompat）
- 不申请普通 runtime permission，通过 `Settings.ACTION_USAGE_ACCESS_SETTINGS` 引导授权

## 构建

云端构建（推荐）：推送到 GitHub 后，GitHub Actions 自动构建 debug APK。

本地构建：

```bash
./gradlew assembleDebug
```

## 安装

将 `app/build/outputs/apk/debug/app-debug.apk` 传到荣耀手机上安装，然后在
「设置 → 应用 → 特殊访问权限 → 使用情况访问」里授予本 App 权限。
