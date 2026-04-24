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
            // TODO: 実際の API エンドポイントに置き換える
            // val response = apiService.getProductLabels()
            // val codes = response.body() ?: return 0
            val codes = simulateFetchProductLabels()

            repository.replaceProductLabels(codes)
            Log.i(TAG, "製品コード同期完了: ${codes.size}件")
            codes.size
        } catch (e: Exception) {
            Log.e(TAG, "製品コード同期エラー: ${e.message}", e)
            0
        }
    }

    /**
     * API呼び出しのシミュレーション（登録データ同期用モック）
     * 実運用時にはRetrofit等による実際のAPI通信に置き換え
     */
    private suspend fun simulateApiCall(): Boolean {
        kotlinx.coroutines.delay(500)
        return true
    }

    /**
     * 製品コード取得 API のモック実装。
     * 実運用時は以下のように Retrofit に置き換える:
     *
     *   interface ApiService {
     *       @GET("api/product-labels")
     *       suspend fun getProductLabels(): Response<List<String>>
     *   }
     *
     * モックは assets の product_labels.txt を読み返すことで
     * 実際の API と同じ動作をシミュレートする。
     */
    private suspend fun simulateFetchProductLabels(): List<String> {
        kotlinx.coroutines.delay(300)
        return try {
            context.assets.open("product_labels.txt")
                .bufferedReader(Charsets.UTF_8)
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.all { c -> c.code < 128 } }
                .distinct()
        } catch (e: Exception) {
            Log.e(TAG, "モック製品コード読み込み失敗", e)
            emptyList()
        }
    }
}
