package com.example.mainproject.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.example.mainproject.data.db.entity.PendingRegistration
import com.example.mainproject.data.db.entity.PendingRegistrationItem

@Dao
interface RegistrationDao {

    // 一括登録を1件保存（親＋子テーブルをまとめて）
    @Transaction
    suspend fun insertRegistrationWithItems(
        registration: PendingRegistration,
        items: List<PendingRegistrationItem>
    ) {
        val id = insertRegistration(registration)
        val itemsWithId = items.map { it.copy(registration_id = id) }
        insertItems(itemsWithId)
    }

    @Insert
    suspend fun insertRegistration(registration: PendingRegistration): Long

    @Insert
    suspend fun insertItems(items: List<PendingRegistrationItem>)

    // 同期待ちデータを取得（pending + 3回未満のfailedも対象）
    @Query("SELECT * FROM pending_registrations WHERE sync_status IN ('pending', 'failed') AND retry_count < 3")
    suspend fun getPendingRegistrations(): List<PendingRegistration>

    // 同期ステータスを更新
    @Query("UPDATE pending_registrations SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, status: String)

    // リトライ回数を更新
    @Query("UPDATE pending_registrations SET retry_count = retry_count + 1, error_message = :error WHERE id = :id")
    suspend fun incrementRetryCount(id: Long, error: String)

    // 特定の登録に紐づく製品番号一覧を取得
    @Query("SELECT * FROM pending_registration_items WHERE registration_id = :registrationId ORDER BY display_order")
    suspend fun getItemsByRegistrationId(registrationId: Long): List<PendingRegistrationItem>
}
