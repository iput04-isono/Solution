package com.example.mainproject.data.repository

import com.example.mainproject.data.db.dao.RegistrationDao
import com.example.mainproject.data.db.entity.PendingRegistration
import com.example.mainproject.data.db.entity.PendingRegistrationItem
import java.time.Instant

class RegistrationRepository(private val dao: RegistrationDao) {

    // 一括登録を保存（親テーブル + 子テーブル）
    suspend fun saveRegistration(
        processId: Int,
        division: String,
        workerId: Int,
        deviceId: String,
        productNumbers: List<String>
    ) {
        val now = Instant.now().toString()

        val registration = PendingRegistration(
            process_id = processId,
            division = division,
            worker_id = workerId,
            device_id = deviceId,
            registered_at = now,
            created_at = now,
            sync_status = "pending"
        )

        val items = productNumbers.mapIndexed { index, productNumber ->
            PendingRegistrationItem(
                registration_id = 0, // insertRegistrationWithItems 内で上書きされる
                product_number = productNumber,
                display_order = index,
                created_at = now
            )
        }

        dao.insertRegistrationWithItems(registration, items)
    }

    // 同期待ちデータを取得
    suspend fun getPendingRegistrations() = dao.getPendingRegistrations()

    // 同期ステータスを更新
    suspend fun updateSyncStatus(id: Long, status: String) =
        dao.updateSyncStatus(id, status)

    // リトライ回数を更新
    suspend fun incrementRetryCount(id: Long, error: String) =
        dao.incrementRetryCount(id, error)

    // 製品番号一覧を取得
    suspend fun getItemsByRegistrationId(registrationId: Long) =
        dao.getItemsByRegistrationId(registrationId)
}
