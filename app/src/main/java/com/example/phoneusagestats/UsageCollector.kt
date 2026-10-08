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
 * 只保存原始数据（各 App 前台秒数 + 解锁次数），不做任何分类/映射，映射由 PC 端完成。
 * 幂等：每次重新查询今天 00:00 → 当前时间，并覆盖写当天 JSON。
 */
object UsageCollector {

    private const val TAG = "UsageCollector"
    private const val PREFS = "collect_prefs"
    private const val KEY_LAST_TIME = "last_collect_time"

    // 每日自动采集的三个时间点（都在 22:00~23:45 窗口内）：
    // 22:30、23:00 兜底，23:30 最晚、数据最全。三个都失败的概率极低，保证「至少一个」。
    private const val WORK_2230 = "phone_usage_evening_2230"
    private const val WORK_2300 = "phone_usage_evening_2300"
    private const val WORK_2330 = "phone_usage_evening_2330"

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

    /** 采集今天 00:00→现在的原始数据并覆盖写当天 JSON。幂等：同一天重复执行结果一致。 */
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

            // 1) 各 App 前台时长（按包名合并，排除自身）。只存原始数据，不分类。
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

            val appsArr = JSONArray()
            for (a in apps) {
                appsArr.put(JSONObject().apply {
                    put("label", a.label)
                    put("packageName", a.packageName)
                    put("foregroundSeconds", a.seconds)
                })
            }

            // 2) 解锁次数 = 当天 KEYGUARD_HIDDEN 事件数（原始直接统计）
            var unlock = 0
            val usageEvents = usm.queryEvents(startOfDay, now)
            val ev = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) unlock++
            }

            val root = JSONObject().apply {
                put("date", date)
                put("generatedAt", timeFmt.format(Date(now)))
                put("timezone", TimeZone.getDefault().id)
                put("unlockCount", unlock)
                put("apps", appsArr)
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

    /** 调度每天 22:30 与 23:30 两次采集（幂等，KEEP 策略不会重复调度）。 */
    fun schedule(context: Context) {
        scheduleDaily(context, WORK_2230, 22, 30)
        scheduleDaily(context, WORK_2300, 23, 0)
        scheduleDaily(context, WORK_2330, 23, 30)
    }

    private fun scheduleDaily(context: Context, name: String, hour: Int, minute: Int) {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = next.timeInMillis - now.timeInMillis
        val request = PeriodicWorkRequestBuilder<CollectWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            name, ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    /** 下次预计执行时间（两次任务里最早的那次）；拿不到返回 null。 */
    fun nextScheduledTime(context: Context): Long? = try {
        val wm = WorkManager.getInstance(context)
        val times = mutableListOf<Long>()
        for (name in listOf(WORK_2230, WORK_2300, WORK_2330)) {
            val next = wm.getWorkInfosForUniqueWork(name).get().firstOrNull()?.nextScheduleTimeMillis
            if (next != null && next > 0) times.add(next)
        }
        times.minOrNull()
    } catch (e: Exception) {
        null
    }

    fun lastCollectTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_TIME, 0L)
}
