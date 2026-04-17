package com.crossvision.f.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.crossvision.f.data.api.RetrofitClient
import com.crossvision.f.data.model.RegistrationRequest
import com.crossvision.f.data.model.SyncStatus
import com.crossvision.f.data.repository.AppRepository

/**
 * オフライン同期マネージャー
 * ネットワーク状態を監視し、未送信データを Flask サーバーへ同期する
 */
class SyncManager(private val context: Context) {

    companion object {
        private const val TAG = "SyncManager"
    }

    private val repository = AppRepository(context)

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
     * @return 同期成功した件数
     */
    suspend fun syncPendingRegistrations(): Int {
        if (!isNetworkAvailable()) {
            Log.d(TAG, "ネットワーク未接続のためスキップ")
            return 0
        }

        val unsyncedItems = repository.getUnsyncedRegistrations()
        Log.d(TAG, "未同期件数: ${unsyncedItems.size}")
        var syncedCount = 0

        for (item in unsyncedItems) {
            try {
                // ステータスを「送信中」に更新
                repository.updateSyncStatus(item.id, SyncStatus.SYNCING)

                // Flask サーバーへ POST
                val request = RegistrationRequest(
                    productCode      = item.productCode,
                    constructionName = item.constructionName,
                    processName      = item.processName,
                    userId           = item.userId,
                    registeredAt     = item.registeredAt
                )

                val response = RetrofitClient.apiService.postRegistration(request)

                if (response.isSuccessful && response.body()?.success == true) {
                    repository.updateSyncStatus(
                        item.id,
                        SyncStatus.SYNCED,
                        System.currentTimeMillis()
                    )
                    syncedCount++
                    Log.d(TAG, "送信成功: ${item.productCode}")
                } else {
                    val code = response.code()
                    val msg  = response.body()?.message ?: response.message()
                    Log.w(TAG, "サーバーエラー ($code): $msg")
                    repository.updateSyncStatus(item.id, SyncStatus.FAILED)
                }
            } catch (e: Exception) {
                // ネットワーク障害・タイムアウトなど
                Log.e(TAG, "通信エラー: ${e.message}", e)
                repository.updateSyncStatus(item.id, SyncStatus.FAILED)
            }
        }

        return syncedCount
    }
}

