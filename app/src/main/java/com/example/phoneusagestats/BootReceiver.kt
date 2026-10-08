package com.example.phoneusagestats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用更新后重新调度采集任务。
 * 由于 schedule() 使用 KEEP 策略且是唯一任务，重复调度是幂等的。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(TAG, "收到 $action，重新调度采集任务")
            UsageCollector.schedule(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
