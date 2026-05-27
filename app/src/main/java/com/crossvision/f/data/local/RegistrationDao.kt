package com.crossvision.f.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.crossvision.f.data.model.Registration
import com.crossvision.f.data.model.PendingRegistration
import com.crossvision.f.data.model.PendingRegistrationItem
import com.crossvision.f.data.model.SyncStatus

@Dao
interface RegistrationDao {

    // === UI表示用のJOINクエリ（旧Registrationモデル互換） ===

    @Query("""
        SELECT 
            i.id as id,
            i.productCode as productCode,
            r.constructionName as constructionName,
            r.processName as processName,
            r.warehouseNo as warehouseNo,
            r.columnNo as columnNo,
            r.tierNo as tierNo,
            r.syncStatus as syncStatus,
            r.registeredAt as registeredAt,
            r.syncedAt as syncedAt,
            r.userId as userId,
            r.imagePath as imagePath
        FROM pending_registration_items i
        INNER JOIN pending_registrations r ON i.registrationId = r.id
        ORDER BY r.registeredAt DESC
    """)
    fun getAll(): LiveData<List<Registration>>

    @Query("""
        SELECT 
            i.id as id,
            i.productCode as productCode,
            r.constructionName as constructionName,
            r.processName as processName,
            r.warehouseNo as warehouseNo,
            r.columnNo as columnNo,
            r.tierNo as tierNo,
            r.syncStatus as syncStatus,
            r.registeredAt as registeredAt,
            r.syncedAt as syncedAt,
            r.userId as userId,
            r.imagePath as imagePath
        FROM pending_registration_items i
        INNER JOIN pending_registrations r ON i.registrationId = r.id
        ORDER BY r.registeredAt DESC
    """)
    suspend fun getAllSync(): List<Registration>

    @Query("""
        SELECT 
            i.id as id,
            i.productCode as productCode,
            r.constructionName as constructionName,
            r.processName as processName,
            r.warehouseNo as warehouseNo,
            r.columnNo as columnNo,
            r.tierNo as tierNo,
            r.syncStatus as syncStatus,
            r.registeredAt as registeredAt,
            r.syncedAt as syncedAt,
            r.userId as userId,
            r.imagePath as imagePath
        FROM pending_registration_items i
        INNER JOIN pending_registrations r ON i.registrationId = r.id
        WHERE r.syncStatus = :status
        ORDER BY r.registeredAt ASC
    """)
    suspend fun getByStatus(status: SyncStatus): List<Registration>

    @Query("""
        SELECT 
            i.id as id,
            i.productCode as productCode,
            r.constructionName as constructionName,
            r.processName as processName,
            r.warehouseNo as warehouseNo,
            r.columnNo as columnNo,
            r.tierNo as tierNo,
            r.syncStatus as syncStatus,
            r.registeredAt as registeredAt,
            r.syncedAt as syncedAt,
            r.userId as userId,
            r.imagePath as imagePath
        FROM pending_registration_items i
        INNER JOIN pending_registrations r ON i.registrationId = r.id
        WHERE r.syncStatus IN ('PENDING', 'FAILED', 'SYNCING')
        ORDER BY r.registeredAt ASC
    """)
    suspend fun getUnsyncedRegistrations(): List<Registration>

    @Query("""
        SELECT COUNT(i.id) 
        FROM pending_registration_items i
        INNER JOIN pending_registrations r ON i.registrationId = r.id
        WHERE r.syncStatus IN ('PENDING', 'FAILED', 'SYNCING')
    """)
    fun getUnsyncedCount(): LiveData<Int>

    @Query("""
        SELECT 
            i.id as id,
            i.productCode as productCode,
            r.constructionName as constructionName,
            r.processName as processName,
            r.warehouseNo as warehouseNo,
            r.columnNo as columnNo,
            r.tierNo as tierNo,
            r.syncStatus as syncStatus,
            r.registeredAt as registeredAt,
            r.syncedAt as syncedAt,
            r.userId as userId,
            r.imagePath as imagePath
        FROM pending_registration_items i
        INNER JOIN pending_registrations r ON i.registrationId = r.id
        WHERE i.productCode LIKE '%' || :query || '%' OR r.constructionName LIKE '%' || :query || '%'
        ORDER BY r.registeredAt DESC
    """)
    fun search(query: String): LiveData<List<Registration>>


    // === 親子構造用の操作メソッド ===

    @Transaction
    suspend fun insertRegistrationWithItems(
        registration: PendingRegistration,
        items: List<PendingRegistrationItem>
    ): Long {
        val parentId = insertRegistration(registration)
        val itemsWithParentId = items.map { it.copy(registrationId = parentId) }
        insertItems(itemsWithParentId)
        return parentId
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegistration(registration: PendingRegistration): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PendingRegistrationItem>)

    @Query("SELECT * FROM pending_registrations WHERE syncStatus IN ('PENDING', 'FAILED') AND retryCount < 3 ORDER BY registeredAt ASC")
    suspend fun getPendingRegistrations(): List<PendingRegistration>

    @Query("SELECT * FROM pending_registration_items WHERE registrationId = :registrationId ORDER BY displayOrder ASC")
    suspend fun getItemsByRegistrationId(registrationId: Long): List<PendingRegistrationItem>

    @Query("UPDATE pending_registrations SET syncStatus = :status, syncedAt = :syncedAt WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, status: SyncStatus, syncedAt: Long? = null)

    @Query("UPDATE pending_registrations SET retryCount = retryCount + 1, errorMessage = :error, syncStatus = 'FAILED' WHERE id = :id")
    suspend fun incrementRetryCount(id: Long, error: String)

    @Delete
    suspend fun deleteRegistration(registration: PendingRegistration)

    @Query("DELETE FROM pending_registrations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
