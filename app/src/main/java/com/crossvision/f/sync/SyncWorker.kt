package com.crossvision.f.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * WorkManagerによるバックグラウンド同期ワーカー
 * オンライン復帰時に未送信データを自動的にサーバーへ同期する
 */
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val TAG = "SyncWorker"
        private const val UNIQUE_WORK_NAME = "crossvision_sync_work"

        /**
         * 定期同期のスケジュール（15分間隔）
         * ネットワーク接続がある場合のみ実行
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        /**
         * 即座に同期を実行（手動同期ボタン用）
         */
        fun executeImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "同期処理を開始します...")

        return try {
            val syncManager = SyncManager(applicationContext)
            val syncedCount = syncManager.syncPendingRegistrations()

            Log.i(TAG, "同期完了: ${syncedCount}件を送信しました")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "同期エラー: ${e.message}", e)
            Result.retry()
        }
    }
}
