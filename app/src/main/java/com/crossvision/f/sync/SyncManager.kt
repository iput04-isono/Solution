package com.crossvision.f.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.crossvision.f.data.model.SyncStatus
import com.crossvision.f.data.repository.AppRepository

/**
 * オフライン同期マネージャー
 * ネットワーク状態を監視し、未送信データのサーバー同期を管理
 */
class SyncManager(private val context: Context) {

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
                    process_id = 1, // TODO: IDの動的取得
                    division = if (item.processName.contains("入")) "start" else "end",
                    worker_id = 1,
                    device_id = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID),
                    registered_at = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date(item.registeredAt)),
                    product_numbers = listOf(item.productCode)
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
}
