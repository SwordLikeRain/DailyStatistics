package com.example.phoneusagestats

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UsageStatsTest"
    }

    private lateinit var statusText: TextView
    private lateinit var scheduleStatusText: TextView
    private lateinit var listView: ListView
    private lateinit var btnOpenSettings: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnQueryEvents: Button
    private lateinit var btnGenerateJson: Button
    private lateinit var btnGrantStorage: Button
    private lateinit var eventsScrollView: ScrollView
    private lateinit var eventsOutputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scheduleStatusText = findViewById(R.id.scheduleStatusText)
        listView = findViewById(R.id.listView)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnQueryEvents = findViewById(R.id.btnQueryEvents)
        btnGenerateJson = findViewById(R.id.btnGenerateJson)
        btnGrantStorage = findViewById(R.id.btnGrantStorage)
        eventsScrollView = findViewById(R.id.eventsScrollView)
        eventsOutputText = findViewById(R.id.eventsOutputText)

        btnOpenSettings.setOnClickListener {
            // PACKAGE_USAGE_STATS 不是普通 runtime permission，不能用 requestPermissions()。
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Log.e(TAG, "无法打开使用情况访问设置页面", e)
                statusText.text = "无法打开使用情况访问设置页面：" + e.message
            }
        }

        btnRefresh.setOnClickListener { refresh() }

        btnQueryEvents.setOnClickListener { queryTodayEvents() }

        btnGenerateJson.setOnClickListener {
            scheduleStatusText.text = "正在生成 JSON…"
            Thread {
                val outcome = UsageCollector.collectAndWrite(applicationContext)
                runOnUiThread {
                    val msg = if (outcome.success) {
                        "JSON 已生成：${outcome.filePath}"
                    } else {
                        "JSON 生成失败：${outcome.message}"
                    }
                    scheduleStatusText.text = msg
                    Toast.makeText(
                        this,
                        if (outcome.success) "JSON 已生成" else "生成失败：${outcome.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    updateScheduleStatus()
                }
            }.start()
        }

        btnGrantStorage.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e2: Exception) {
                        Log.e(TAG, "无法打开所有文件访问设置", e2)
                    }
                }
            } else {
                requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1001)
            }
        }

        // 确保每日采集任务已调度（幂等，KEEP 策略；首次打开 / 重启后都会走到这里）
        UsageCollector.schedule(this)
        // 「打开 App 立即采集一次」：后台被杀后第一次回来，必定立即刷新一次 JSON
        triggerImmediateCollect()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateScheduleStatus()
    }

    private fun refresh() {
        if (!hasUsageAccessPermission()) {
            statusText.text = getString(R.string.status_no_permission)
            btnOpenSettings.visibility = View.VISIBLE
            listView.adapter = null
            listView.visibility = View.VISIBLE
            eventsScrollView.visibility = View.GONE
            return
        }

        statusText.text = "正在读取今天的使用数据…"
        btnOpenSettings.visibility = View.GONE
        listView.visibility = View.VISIBLE
        eventsScrollView.visibility = View.GONE

        try {
            val items = queryTodayUsageStats()
            statusText.text = if (items.isEmpty()) {
                "今天还没有任何 App 的前台使用记录（totalTimeInForeground 均为 0）。"
            } else {
                "共 ${items.size} 个 App 今天有前台使用时长："
            }
            listView.adapter = AppUsageAdapter(items)
        } catch (e: Exception) {
            Log.e(TAG, "读取使用统计失败", e)
            statusText.text = "读取使用统计失败：" + e.message
            listView.adapter = null
        }
    }

    private fun triggerImmediateCollect() {
        Thread {
            val outcome = UsageCollector.collectAndWrite(applicationContext)
            Log.i(TAG, "打开即采集 success=${outcome.success} msg=${outcome.message}")
            runOnUiThread { updateScheduleStatus() }
        }.start()
    }

    private fun updateScheduleStatus() {
        val last = UsageCollector.lastCollectTime(this)
        val lastStr = if (last > 0) formatDateTime(last) else "从未"
        val storageOk = UsageCollector.hasStorageAccess(this)
        btnGrantStorage.visibility = if (storageOk) View.GONE else View.VISIBLE

        Thread {
            val next = UsageCollector.nextScheduledTime(this)
            val nextStr =
                if (next != null && next > 0 && next < Long.MAX_VALUE) formatDateTime(next) else "未知"
            runOnUiThread {
                scheduleStatusText.text = "上次采集：$lastStr\n下次预计：$nextStr\n存储权限：${if (storageOk) "已授权" else "未授权"}"
            }
        }.start()
    }

    private fun formatDateTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    /**
     * 判断是否已授予「使用情况访问」权限。
     * 该权限不能通过 requestPermissions() 申请，只能通过 AppOps 检查当前是否已被授予。
     */
    private fun hasUsageAccessPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "检查使用情况访问权限失败", e)
            false
        }
    }

    private fun queryTodayUsageStats(): List<AppUsageItem> {
        val usageStatsManager =
            getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        val stats: List<UsageStats> = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            now
        ) ?: emptyList()

        val totalByPackage = LinkedHashMap<String, Long>()
        for (s in stats) {
            if (s.totalTimeInForeground <= 0) continue
            totalByPackage[s.packageName] =
                (totalByPackage[s.packageName] ?: 0L) + s.totalTimeInForeground
        }

        val pm = packageManager
        val result = mutableListOf<AppUsageItem>()
        for ((pkg, time) in totalByPackage) {
            // 可选：排除本 App 自身。如需保留，注释掉下一行即可。
            if (pkg == packageName) continue

            result.add(
                AppUsageItem(
                    label = getAppLabel(pm, pkg),
                    packageName = pkg,
                    totalTimeInForeground = time
                )
            )
        }

        result.sortByDescending { it.totalTimeInForeground }
        return result
    }

    /** 获取 App 名称；拿不到时用 packageName 兜底。 */
    private fun getAppLabel(pm: PackageManager, packageName: String): String {
        return try {
            val appInfo: ApplicationInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private data class AppUsageItem(
        val label: String,
        val packageName: String,
        val totalTimeInForeground: Long
    )

    private inner class AppUsageAdapter(items: List<AppUsageItem>) :
        ArrayAdapter<AppUsageItem>(this, R.layout.item_app, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_app, parent, false)
            val item = getItem(position)!!

            val nameTimeText = view.findViewById<TextView>(R.id.nameTimeText)
            val packageText = view.findViewById<TextView>(R.id.packageText)

            nameTimeText.text = item.label + "  " + formatDuration(item.totalTimeInForeground)
            packageText.text = item.packageName
            return view
        }
    }

    /**
     * 时长格式化：
     *  - 少于 1 分钟：X 秒
     *  - 少于 1 小时：X 分钟
     *  - 1 小时及以上：X小时X分钟
     */
    private fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        if (totalSeconds < 60) {
            return "${totalSeconds} 秒"
        }
        val totalMinutes = totalSeconds / 60
        if (totalMinutes < 60) {
            return "${totalMinutes} 分钟"
        }
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return "${hours}小时${minutes}分钟"
    }

    /**
     * 查询今天 00:00 到当前的 Usage Events，统计并展示屏幕交互 / Keyguard 事件。
     * 当前只展示原始事件，不做任何「解锁次数」推断。
     */
    private fun queryTodayEvents() {
        if (!hasUsageAccessPermission()) {
            statusText.text = getString(R.string.status_no_permission)
            btnOpenSettings.visibility = View.VISIBLE
            return
        }

        try {
            val usageStatsManager =
                getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis
            val now = System.currentTimeMillis()

            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

            val targetTypes = listOf(
                UsageEvents.Event.SCREEN_INTERACTIVE,
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.KEYGUARD_HIDDEN
            )

            val counts = mutableMapOf<Int, Int>()
            val detailLines = mutableListOf<String>()

            val usageEvents = usageStatsManager.queryEvents(startOfDay, now)
            val event = UsageEvents.Event()
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                val type = event.eventType
                if (type in targetTypes) {
                    counts[type] = (counts[type] ?: 0) + 1
                    val time = timeFmt.format(Date(event.timeStamp))
                    val pkg = event.packageName?.takeIf { it.isNotBlank() } ?: ""
                    detailLines.add("$time  ${eventTypeName(type)}  $pkg".trimEnd())
                }
            }

            val sb = StringBuilder()
            sb.append("今日 Usage Events（自 ")
            sb.append(timeFmt.format(Date(startOfDay)))
            sb.append(" 起，共 ")
            sb.append(detailLines.size)
            sb.append(" 条目标事件）\n\n")
            sb.append("事件计数：\n")
            for (type in targetTypes) {
                sb.append(eventTypeName(type))
                sb.append("  ")
                sb.append(counts[type] ?: 0)
                sb.append("\n")
            }
            sb.append("\n事件明细（按时间顺序）：\n")
            for (line in detailLines) {
                sb.append(line).append("\n")
            }

            eventsOutputText.text = sb.toString()
            eventsScrollView.scrollTo(0, 0)
            listView.visibility = View.GONE
            eventsScrollView.visibility = View.VISIBLE
            statusText.text = "事件查询完成，共 ${detailLines.size} 条"
        } catch (e: Exception) {
            Log.e(TAG, "查询 Usage Events 失败", e)
            statusText.text = "查询 Usage Events 失败：" + e.message
        }
    }

    private fun eventTypeName(type: Int): String = when (type) {
        UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        else -> "EVENT_$type"
    }
}
