package com.example.phoneusagestats

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 负责「今天」使用数据的采集与 JSON 生成。
 * 幂等：每次都是重新查询今天 00:00 → 当前时间，并覆盖写当天 JSON，同一天重复执行结果一致。
 */
object UsageCollector {

    private const val TAG = "UsageCollector"
    private const val PREFS = "collect_prefs"
    private const val KEY_LAST_TIME = "last_collect_time"

    // 需要单独统计的 App（名称 -> packageName）。
    // 注意：图库 / 笔记 / 系统管家服务 / 豆包 的包名在荣耀 MagicOS 上可能与下面不同，
    // 请用本 App 列表里显示的包名核对并修改。
    val TRACKED_APPS = listOf(
        "哔哩哔哩" to "tv.danmaku.bili",
        "起点读书" to "com.qidian.QDReader",
        "飞书" to "com.ss.android.lark",
        "图库" to "com.hihonor.photos",
        "笔记" to "com.hihonor.notepad",
        "系统管家服务" to "com.hihonor.systemmanager",
        "QQ" to "com.tencent.mobileqq",
        "微信" to "com.tencent.mm",
        "豆包" to "com.larus.nova",
        "Chrome" to "com.android.chrome"
    )

    data class Outcome(val success: Boolean, val message: String, val filePath: String? = null)

    private data class AppEntry(val label: String, val packageName: String, val seconds: Long)

    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** 采集今天 00:00→现在的用量并覆盖写当天 JSON。幂等：同一天重复执行结果一致。 */
    fun collectAndWrite(context: Context): Outcome {
        if (!hasUsageAccess(context)) return Outcome(false, "缺少「使用情况访问」权限")
        if (!hasStorageAccess(context)) return Outcome(false, "缺少「所有文件访问」权限")
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis
            val now = System.currentTimeMillis()

            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val date = dateFmt.format(Date(now))

            // 1) 各 App 前台时长（按包名合并，排除自身）
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
                ?: emptyList()
            val byPackage = LinkedHashMap<String, Long>()
            for (s in stats) {
                if (s.totalTimeInForeground <= 0) continue
                if (s.packageName == context.packageName) continue
                byPackage[s.packageName] = (byPackage[s.packageName] ?: 0L) + s.totalTimeInForeground
            }
            val pm = context.packageManager
            val apps = byPackage.map { (pkg, ms) ->
                AppEntry(getAppLabel(pm, pkg), pkg, ms / 1000)
            }.sortedByDescending { it.seconds }

            val totalSeconds = apps.sumOf { it.seconds }

            // 2) 解锁次数 = KEYGUARD_HIDDEN 计数（其它事件一并统计便于调试）
            var unlock = 0
            var keyguardShown = 0
            var screenInteractive = 0
            var screenNonInteractive = 0
            val usageEvents = usm.queryEvents(startOfDay, now)
            val ev = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(ev)
                when (ev.eventType) {
                    UsageEvents.Event.KEYGUARD_HIDDEN -> unlock++
                    UsageEvents.Event.KEYGUARD_SHOWN -> keyguardShown++
                    UsageEvents.Event.SCREEN_INTERACTIVE -> screenInteractive++
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> screenNonInteractive++
                }
            }

            // 3) 单独统计的 App 与「其他」
            val trackedArr = JSONArray()
            var trackedSeconds = 0L
            for ((name, pkg) in TRACKED_APPS) {
                val sec = (byPackage[pkg] ?: 0L) / 1000
                trackedSeconds += sec
                trackedArr.put(JSONObject().apply {
                    put("name", name)
                    put("packageName", pkg)
                    put("foregroundSeconds", sec)
                    put("formatted", formatDuration(sec * 1000))
                })
            }
            val otherSeconds = totalSeconds - trackedSeconds

            // 4) 全部 App
            val appsArr = JSONArray()
            for (a in apps) {
                appsArr.put(JSONObject().apply {
                    put("label", a.label)
                    put("packageName", a.packageName)
                    put("foregroundSeconds", a.seconds)
                    put("formatted", formatDuration(a.seconds * 1000))
                })
            }

            val root = JSONObject().apply {
                put("date", date)
                put("generatedAt", timeFmt.format(Date(now)))
                put("timezone", TimeZone.getDefault().id)
                put("unlockCount", unlock)
                put("keyguardShownCount", keyguardShown)
                put("screenInteractiveCount", screenInteractive)
                put("screenNonInteractiveCount", screenNonInteractive)
                put("totalForegroundSeconds", totalSeconds)
                put("totalFormatted", formatDuration(totalSeconds * 1000))
                put("otherForegroundSeconds", otherSeconds)
                put("otherFormatted", formatDuration(otherSeconds * 1000))
                put("apps", appsArr)
                put("trackedApps", trackedArr)
                put("note", "totalForegroundSeconds 为各 App totalTimeInForeground 之和，口径可能与系统「屏幕使用时间」不同；unlockCount = 当天 KEYGUARD_HIDDEN 事件数。")
            }

            val file = writeJsonToPictures(date, root.toString(2))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_TIME, now).apply()

            Outcome(true, "已写入 ${file.absolutePath}", file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "采集失败", e)
            Outcome(false, "采集失败：" + e.message)
        }
    }

    private fun writeJsonToPictures(date: String, json: String): File {
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = File(pictures, "PhoneUsage")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$date.json")
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    private fun getAppLabel(pm: PackageManager, packageName: String): String = try {
        val ai: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
        pm.getApplicationLabel(ai).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        if (totalSeconds < 60) return "${totalSeconds} 秒"
        val totalMinutes = totalSeconds / 60
        if (totalMinutes < 60) return "${totalMinutes} 分钟"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "${hours}小时${minutes}分钟"
    }

    /** 调度周期性采集任务（幂等，KEEP 策略不会重复调度）。 */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CollectWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            CollectWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** 下次预计执行时间（毫秒）；拿不到返回 null。 */
    fun nextScheduledTime(context: Context): Long? = try {
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(CollectWorker.UNIQUE_WORK_NAME)
            .get()
            .firstOrNull()
            ?.nextScheduleTimeMillis
    } catch (e: Exception) {
        null
    }

    fun lastCollectTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_TIME, 0L)
}
