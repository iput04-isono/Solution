package com.crossvision.f.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 製品コードマスター（Room エンティティ）
 *
 * サーバーから取得した最新の製品コードリストを保持する。
 * LabelMatcher はこのテーブルを参照して OCR 結果と照合する。
 *
 * 更新戦略:
 *   - サーバー同期時に全件削除 → 新規一括挿入（常にサーバーが正）
 *   - テーブルが空の場合は assets/product_labels.txt をフォールバックとして使用
 */
@Entity(tableName = "product_labels")
data class ProductLabel(
    @PrimaryKey
    val code: String,           // 製品コード（例: "B1Sb30N-7A"）
    val updatedAt: Long = System.currentTimeMillis()  // サーバー同期日時（Unix ms）
)
