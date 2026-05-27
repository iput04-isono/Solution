package com.crossvision.f.data.model

/**
 * 登録情報データモデル
 * 製品コードと倉庫位置情報を紐づけて管理（UIおよびドメイン層での受け渡し用）
 */
data class Registration(
    val id: Long = 0,
    val productCode: String,           // 製品コード
    val constructionName: String,      // 工事名
    val processName: String,           // 工程名
    val warehouseNo: String = "",      // 倉庫No
    val columnNo: String = "",         // 列No
    val tierNo: String = "",           // 段No
    val syncStatus: SyncStatus = SyncStatus.PENDING, // 同期ステータス
    val registeredAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,        // 同期完了日時
    val userId: String = "",           // 登録ユーザーID
    val imagePath: String? = null      // 撮影画像のローカルパス
)

/**
 * 同期ステータス
 */
enum class SyncStatus {
    PENDING,    // 未送信（オフライン保存済み）
    SYNCING,    // 送信中
    SYNCED,     // 送信済み
    FAILED      // 送信失敗
}
