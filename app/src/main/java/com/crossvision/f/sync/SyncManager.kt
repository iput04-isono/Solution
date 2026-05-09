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

                // エンティティの userId は String なので、サーバーに合わせて Int に変換する（エラー時は 1）
                val workerId = item.userId.toIntOrNull() ?: 1

                // サーバーへ送信
                val request = com.crossvision.f.data.model.RegistrationRequest(
                    processId = 1, // 工程IDの動的取得が複雑なため現状は1（サーバー側でprocess_nameを優先使用）
                    division = item.processName, // "start"/"end" 固定ではなく、選択された工程名をそのまま送信
                    workerId = workerId,
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
                Log.e("SyncManager", "Sync failed for item ${item.id}", e)
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
            
            // サーバーからデータを取得
            val constResponse = api.getConstructions()
            val procResponse = api.getProcesses()
            
            if (constResponse.isSuccessful && procResponse.isSuccessful) {
                val constructions = constResponse.body() ?: emptyList()
                val processes = procResponse.body() ?: emptyList()
                
                // トランザクションで一括更新
                repository.syncMasterData(constructions, processes)
                Log.i(TAG, "工事・工程同期完了 (工事:${constructions.size}件, 工程:${processes.size}件)")
                true
            } else {
                Log.e(TAG, "同期失敗: 工事=${constResponse.code()}, 工程=${procResponse.code()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "工事・工程同期エラー: ${e.message}", e)
            false
        }
    }
}
