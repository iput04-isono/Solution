package com.crossvision.f.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 未同期の工程登録親テーブル（共通メタデータを保持）
 */
@Entity(tableName = "pending_registrations")
data class PendingRegistration(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val constructionName: String,
    val processName: String,
    val warehouseNo: String = "",
    val columnNo: String = "",
    val tierNo: String = "",
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val registeredAt: Long = System.currentTimeMillis(),
    val syncedAt: Long? = null,
    val userId: String = "",
    val imagePath: String? = null,
    val retryCount: Int = 0,
    val errorMessage: String? = null
)
