package com.crossvision.f.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.crossvision.f.data.model.SyncStatus
import com.crossvision.f.data.repository.AppRepository

/**
 * オフライン同期マネージャー
 * ネットワーク状態を監視し、未送信データのサーバー同期を管理
 */
class SyncManager(private val context: Context) {

    private val repository = AppRepository(context)

    companion object {
        private const val TAG = "SyncManager"

        /**
         * 製品コードマスターの同期間隔（24時間）
         * 前回同期から SYNC_INTERVAL_MS 経過していない場合はスキップする
         */
        private const val SYNC_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }

    /**
     * ネットワーク接続状態を確認
     */
    fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 未送信データをサーバーに同期する
     */
    suspend fun syncPendingRegistrations(): Int {
        if (!isNetworkAvailable()) return 0

        val unsyncedItems = repository.getUnsyncedRegistrations()
        var syncedCount = 0

        for (item in unsyncedItems) {
            try {
                repository.updateSyncStatus(item.id, SyncStatus.SYNCING)

                // サーバーへ送信
                val request = com.crossvision.f.data.model.RegistrationRequest(
                    processId = 1, // TODO: IDの動的取得
                    division = if (item.processName.contains("入")) "start" else "end",
                    workerId = 1,
                    deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID),
                    registeredAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date(item.registeredAt)),
                    productNumbers = listOf(item.productCode),
                    constructionName = item.constructionName,
                    processName = item.processName
                )

                val response = com.crossvision.f.data.api.RetrofitClient.apiService.postRegistration(request)

                if (response.isSuccessful && response.body()?.success == true) {
                    repository.updateSyncStatus(
                        item.id,
                        SyncStatus.SYNCED,
                        System.currentTimeMillis()
                    )
                    syncedCount++
                } else {
                    repository.updateSyncStatus(item.id, SyncStatus.FAILED)
                }
            } catch (e: Exception) {
                android.util.Log.e("SyncManager", "Sync failed for item ${item.id}", e)
                repository.updateSyncStatus(item.id, SyncStatus.FAILED)
            }
        }

        return syncedCount
    }

    /**
     * 製品コードマスターをサーバーから取得して DB を更新する。
     *
     * 処理フロー:
     *   1. ネットワーク接続を確認
     *   2. 前回同期から 24 時間以内であればスキップ
     *   3. サーバーから製品コードリストを取得（GET /api/product-labels）
     *   4. DB の product_labels テーブルを全削除 → 新規一括挿入
     *
     * @return 同期した件数（スキップ時は -1、失敗時は 0）
     */
    suspend fun syncProductLabels(): Int {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "製品コード同期スキップ: ネットワーク未接続")
            return -1
        }

        // 24時間以内に同期済みであればスキップ
        val lastSynced = repository.getProductLabelLastSyncedAt()
        if (lastSynced != null && System.currentTimeMillis() - lastSynced < SYNC_INTERVAL_MS) {
            Log.d(TAG, "製品コード同期スキップ: 前回同期から24時間以内")
            return -1
        }

        return try {
            val response = com.crossvision.f.data.api.RetrofitClient.apiService.getProductLabels()
            if (response.isSuccessful) {
                val codes = response.body() ?: return 0
                repository.replaceProductLabels(codes)
                Log.i(TAG, "製品コード同期完了: ${codes.size}件")
                codes.size
            } else {
                Log.e(TAG, "製品コード同期失敗: HTTP ${response.code()}")
                0
            }
        } catch (e: Exception) {
            Log.e(TAG, "製品コード同期エラー: ${e.message}", e)
            0
        }
    }

    /**
     * 工事と工程のマスターデータをサーバーから取得して DB を更新する。
     */
    suspend fun syncConstructionsAndProcesses(): Boolean {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "工事・工程同期スキップ: ネットワーク未接続")
            return false
        }
        
        return try {
            val api = com.crossvision.f.data.api.RetrofitClient.apiService
            
            // 工事データの同期
            val constResponse = api.getConstructions()
            if (constResponse.isSuccessful) {
                val constructions = constResponse.body() ?: emptyList()
                repository.replaceConstructions(constructions)
            } else {
                Log.e(TAG, "工事同期失敗: HTTP ${constResponse.code()}")
                return false
            }

            // 工程データの同期
            val procResponse = api.getProcesses()
            if (procResponse.isSuccessful) {
                val processes = procResponse.body() ?: emptyList()
                repository.replaceProcesses(processes)
            } else {
                Log.e(TAG, "工程同期失敗: HTTP ${procResponse.code()}")
                return false
            }

            Log.i(TAG, "工事・工程同期完了")
            true
        } catch (e: Exception) {
            Log.e(TAG, "工事・工程同期エラー: ${e.message}", e)
            false
        }
    }
}
