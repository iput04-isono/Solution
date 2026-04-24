package com.crossvision.f.data.local

import androidx.room.*
import com.crossvision.f.data.model.ProductLabel

/**
 * 製品コードマスターの DB 操作インターフェース
 */
@Dao
interface ProductLabelDao {

    /** 全件取得（LabelMatcher が照合リストとして使用） */
    @Query("SELECT * FROM product_labels ORDER BY code ASC")
    suspend fun getAll(): List<ProductLabel>

    /** 件数確認（0件ならフォールバックが必要と判断する） */
    @Query("SELECT COUNT(*) FROM product_labels")
    suspend fun count(): Int

    /** 最終同期日時を取得（不要な再同期を防ぐ） */
    @Query("SELECT MAX(updatedAt) FROM product_labels")
    suspend fun lastSyncedAt(): Long?

    /** サーバーから取得した新しいリストを一括挿入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(labels: List<ProductLabel>)

    /** 同期前に旧データを全削除（サーバーのリストで完全に置き換える） */
    @Query("DELETE FROM product_labels")
    suspend fun deleteAll()
}
