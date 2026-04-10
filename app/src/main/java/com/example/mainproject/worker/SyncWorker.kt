package com.example.mainproject.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.mainproject.data.api.RetrofitClient
import com.example.mainproject.data.db.AppDatabase
import com.example.mainproject.data.model.RegistrationRequest
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.registrationDao()
        val apiService = RetrofitClient.apiService

        // 同期待ちのデータを取得
        val pendingList = dao.getPendingRegistrations()
        if (pendingList.isEmpty()) return Result.success()

        for (registration in pendingList) {
            try {
                // 同期中に更新
                dao.updateSyncStatus(registration.id, "syncing")

                // 紐づく製品番号を取得
                val items = dao.getItemsByRegistrationId(registration.id)
                val productNumbers = items.map { it.product_number }

                // Retrofit でサーバーに送信
                val request = RegistrationRequest(
                    process_id = registration.process_id,
                    division = registration.division,
                    worker_id = registration.worker_id,
                    device_id = registration.device_id,
                    registered_at = registration.registered_at,
                    product_numbers = productNumbers
                )
                val response = apiService.postRegistration(request)

                if (response.isSuccessful) {
                    // 成功したら synced に更新
                    dao.updateSyncStatus(registration.id, "synced")
                } else {
                    // サーバーエラー
                    throw Exception("Server error: ${response.code()} ${response.message()}")
                }

            } catch (e: Exception) {
                // 失敗したらリトライ回数を増やして failed に更新
                dao.incrementRetryCount(registration.id, e.message ?: "Unknown error")
                dao.updateSyncStatus(registration.id, "failed")

                // 最大リトライ回数（3回）を超えていたら失敗を返す
                val updated = dao.getPendingRegistrations()
                    .find { it.id == registration.id }
                if ((updated?.retry_count ?: 0) >= 3) {
                    return Result.failure()
                }
                return Result.retry()
            }
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "sync_worker"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
