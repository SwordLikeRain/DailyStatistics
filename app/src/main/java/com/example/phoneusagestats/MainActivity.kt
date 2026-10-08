package com.example.phoneusagestats

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UsageStatsTest"
    }

    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var btnOpenSettings: Button
    private lateinit var btnRefresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        listView = findViewById(R.id.listView)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnRefresh = findViewById(R.id.btnRefresh)

        btnOpenSettings.setOnClickListener {
            // PACKAGE_USAGE_STATS 不是普通 runtime permission，不能用 requestPermissions()。
            // 必须引导用户去系统设置页面手动授权。
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (e: Exception) {
                Log.e(TAG, "无法打开使用情况访问设置页面", e)
                statusText.text = "无法打开使用情况访问设置页面：" + e.message
            }
        }

        btnRefresh.setOnClickListener {
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回到界面都刷新一次，这样从系统设置页授权返回后能立即看到结果。
        refresh()
    }

    private fun refresh() {
        if (!hasUsageAccessPermission()) {
            statusText.text = getString(R.string.status_no_permission)
            btnOpenSettings.visibility = View.VISIBLE
            listView.adapter = null
            return
        }

        statusText.text = "正在读取今天的使用数据…"
        btnOpenSettings.visibility = View.GONE

        try {
            val items = queryTodayUsageStats()
            statusText.text = if (items.isEmpty()) {
                "今天还没有任何 App 的前台使用记录（totalTimeInForeground 均为 0）。"
            } else {
                "共 ${items.size} 个 App 今天有前台使用时长："
            }
            listView.adapter = AppUsageAdapter(items)
        } catch (e: Exception) {
            // 不静默吞掉异常：记录日志并显示给用户。
            Log.e(TAG, "读取使用统计失败", e)
            statusText.text = "读取使用统计失败：" + e.message
            listView.adapter = null
        }
    }

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

        // 今天 00:00:00（使用系统当前时区）。Calendar 默认就是系统时区。
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        // 查询 [今天 00:00, 当前时刻) 范围内的日粒度 UsageStats。
        val stats: List<UsageStats> = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            now
        ) ?: emptyList()

        // queryUsageStats 可能对同一个 packageName 返回多条记录（多个 bucket），
        // 这里按 packageName 合并，把每个 App 的前台时长累加，避免同一 App 出现多行。
        val totalByPackage = LinkedHashMap<String, Long>()
        for (s in stats) {
            if (s.totalTimeInForeground <= 0) continue
            totalByPackage[s.packageName] =
                (totalByPackage[s.packageName] ?: 0L) + s.totalTimeInForeground
        }

        val pm = packageManager
        val result = mutableListOf<AppUsageItem>()
        for ((pkg, time) in totalByPackage) {
            // 可选：排除本 App 自身，避免它出现在统计列表里干扰与系统数据的对比。
            // 如需保留本 App 的数据，注释掉下面这一行即可。
            if (pkg == packageName) continue

            result.add(
                AppUsageItem(
                    label = getAppLabel(pm, pkg),
                    packageName = pkg,
                    totalTimeInForeground = time
                )
            )
        }

        // 按前台使用时长从高到低排序。
        result.sortByDescending { it.totalTimeInForeground }
        return result
    }

    /** 获取 App 名称；拿不到时用 packageName 兜底，确保每一行都有可读内容。 */
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
}
