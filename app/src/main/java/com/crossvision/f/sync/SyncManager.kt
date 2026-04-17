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
     * オンライン復帰時に呼び出される
     *
     * @return 同期成功した件数
     */
    suspend fun syncPendingRegistrations(): Int {
        if (!isNetworkAvailable()) return 0

        val unsyncedItems = repository.getUnsyncedRegistrations()
        var syncedCount = 0

        for (item in unsyncedItems) {
            try {
                // ステータスを「送信中」に更新
                repository.updateSyncStatus(item.id, SyncStatus.SYNCING)

                // TODO: 実際のAPI呼び出しに置き換える
                // val response = apiService.sendRegistration(item)
                val isSuccess = simulateApiCall()

                if (isSuccess) {
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
                // 通信エラー時は失敗ステータスに戻す（データは保持）
                repository.updateSyncStatus(item.id, SyncStatus.FAILED)
            }
        }

        return syncedCount
    }

    /**
     * API呼び出しのシミュレーション（モック）
     * 実運用時にはRetrofit等による実際のAPI通信に置き換え
     */
    private suspend fun simulateApiCall(): Boolean {
        // 擬似的な遅延（ネットワーク通信を模擬）
        kotlinx.coroutines.delay(500)
        return true // 常に成功を返す（モック）
    }
}
