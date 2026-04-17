package com.crossvision.f.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 工程情報データモデル
 * 工事に紐づく工程（1つの工事に複数の工程が存在）
 */
@Entity(
    tableName = "processes",
    foreignKeys = [
        ForeignKey(
            entity = Construction::class,
            parentColumns = ["id"],
            childColumns = ["constructionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("constructionId")]
)
data class Process(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val constructionId: Long,  // 紐づく工事ID
    val name: String,          // 工程名
    val code: String = "",     // 工程コード
    val isActive: Boolean = true
)
