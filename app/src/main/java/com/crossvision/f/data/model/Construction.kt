package com.crossvision.f.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 工事情報データモデル
 * 工事名と紐づく工程の親テーブル
 */
@Entity(tableName = "constructions")
data class Construction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,          // 工事名
    val code: String = "",     // 工事コード
    val isActive: Boolean = true
)
