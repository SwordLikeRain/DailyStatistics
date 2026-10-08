package com.example.phoneusagestats

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager 后台采集任务：每次重新查询今天 00:00→当前，覆盖写当天 JSON。
 */
class CollectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val outcome = UsageCollector.collectAndWrite(applicationContext)
            Log.i(TAG, "采集完成 success=${outcome.success} msg=${outcome.message}")
            if (outcome.success) Result.success() else Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "采集异常", e)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "CollectWorker"
    }
}
