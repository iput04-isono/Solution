package com.crossvision.f.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 未同期の工程登録子テーブル（一括登録された製品番号リストを保持）
 */
@Entity(
    tableName = "pending_registration_items",
    indices = [
        Index("registrationId"),
        Index("productCode")
    ],
    foreignKeys = [
        ForeignKey(
            entity = PendingRegistration::class,
            parentColumns = ["id"],
            childColumns = ["registrationId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class PendingRegistrationItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val registrationId: Long,
    val productCode: String,
    val displayOrder: Int,
    val registeredAt: Long = System.currentTimeMillis()
)
