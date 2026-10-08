# 今日使用统计（phone-usage-stats）

一个用于在荣耀/MagicOS 手机上测试 Android UsageStats API 的最小 App：读取并展示「今天」各 App 的前台使用时长与解锁次数，并后台自动把当天数据写入 JSON 文件，方便同步到电脑做进一步分析。

## 功能

- 首页直接展示：
  - 今天各 App 前台使用时长（按从高到低排序，含 App 名、packageName）
  - 今日解锁次数（= 当天 `KEYGUARD_HIDDEN` 事件数）
- 「刷新」按钮重新查询当天数据
- 「立即生成 JSON」手动采集一次（有 Toast 提示）
- 后台自动采集（WorkManager）：每天 22:30 / 23:00 / 23:30 各执行一次
- 打开 App 立即采集一次（后台被杀后回来必触发）
- 手机重启后自动恢复调度（BootReceiver）

## 界面

```
今日 App 使用统计
今日解锁次数：19 次
共 20 个 App 今天有前台使用时长：
[刷新]
[立即生成 JSON]
上次采集：2026-10-08 23:03:00
下次预计：2026-10-09 22:30:00
存储权限：已授权
（App 使用时长列表）
```

## JSON 输出

- 位置：`/storage/emulated/0/Pictures/PhoneUsage/YYYY-MM-DD.json`（即系统「图片 / Pictures」目录下的 `PhoneUsage` 子文件夹）
- 每天一个文件，文件名是当天日期，例如 `2026-10-08.json`
- 每次采集整体重算「今天 00:00 → 现在」并**覆盖**当天文件（幂等）
- App **不删除**任何旧文件，历史文件由你自行管理

字段：

```json
{
  "date": "2026-10-08",
  "generatedAt": "2026-10-08 23:03:00",
  "unlockCount": 19,
  "unlockTimesText": ["07:12:40", "09:05:18"],
  "apps": [
    { "label": "哔哩哔哩", "packageName": "tv.danmaku.bili", "foregroundSeconds": 26280 }
  ]
}
```

| 字段 | 含义 |
|------|------|
| date | 采集日期 |
| generatedAt | 本次采集时间 |
| unlockCount | 当天解锁次数（KEYGUARD_HIDDEN 事件数） |
| unlockTimesText | 每次解锁的时间（HH:mm:ss） |
| apps | 当天各 App 的原始前台时长 |
| apps[].label | App 显示名 |
| apps[].packageName | 包名（稳定，建议 PC 端以此做映射主键） |
| apps[].foregroundSeconds | 前台时长（秒） |

说明：

- 只保存**原始数据**，不做分类映射，映射由 PC 端完成。
- 已排除本 App 自身。
- `unlockCount` / `unlockTimesText` 来自 UsageEvents 的 `KEYGUARD_HIDDEN` 事件；`apps[].foregroundSeconds` 来自 UsageStats 的 `totalTimeInForeground`（毫秒 ÷ 1000）。

## 采集调度

| 触发方式 | 说明 |
|----------|------|
| 每天 22:30 / 23:00 / 23:30 | WorkManager 周期任务，越晚越准、三重兜底 |
| 打开 App | 每次冷启动立即采集一次 |
| 手机重启 | BootReceiver 重新调度 |

注意：WorkManager 周期任务**不保证精确准点**，系统可能延迟几分钟到几小时（Doze / 省电 / 厂商后台管理）。

## 权限

| 权限 | 用途 | 授权方式 |
|------|------|----------|
| PACKAGE_USAGE_STATS | 读取使用时长与解锁事件 | 系统设置「使用情况访问」手动开启 |
| MANAGE_EXTERNAL_STORAGE | Android 11+ 写 Pictures/PhoneUsage | 系统设置「所有文件访问」手动开启 |
| WRITE_EXTERNAL_STORAGE | Android 10 及以下写文件 | 运行时权限 |
| RECEIVE_BOOT_COMPLETED | 开机后重新调度 | 自动 |

- **无 INTERNET 权限**，App 不会联网、不会上传任何数据。

## 技术栈

- Kotlin + Android Gradle Plugin（AGP 8.7.2）
- Gradle 8.10.2，JDK 17
- minSdk 23 / targetSdk 35 / compileSdk 35
- 依赖：`androidx.appcompat:appcompat:1.7.0`、`androidx.work:work-runtime-ktx:2.9.1`

## 构建（云端，无需本地 Android Studio）

推送到 GitHub 后，GitHub Actions 自动构建 debug APK（`.github/workflows/build-apk.yml`）。

- 构建产物：`phone-usage-stats-debug` artifact → 解压得到 `app-debug.apk`
- 本地构建（可选）：`./gradlew assembleDebug`

## 安装与授权

1. 把 `app-debug.apk` 传到手机安装（如遇版本未更新，先卸载旧版再装）。
2. 打开 App，按提示授权：
   - 「使用情况访问」（设置 → 应用 → 特殊访问权限 → 使用情况访问）
   - 「所有文件访问」（设置 → 应用 → 特殊访问权限 → 所有文件访问）
3. 点「立即生成 JSON」，检查 `Pictures/PhoneUsage/2026-10-08.json` 是否生成。

## 验证

- 手动：点「立即生成 JSON」，检查 `Pictures/PhoneUsage/` 下的 JSON。
- 后台：等一个采集时间点（22:30 / 23:00 / 23:30），或打开 App 看状态栏「上次采集」时间是否更新。
- 重启：重启手机后不打开 App，等一个时间点，看 JSON 是否仍更新。
