package com.crossvision.f.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.crossvision.f.data.model.Registration
import com.crossvision.f.data.model.SyncStatus

@Dao
interface RegistrationDao {

    @Query("SELECT * FROM registrations ORDER BY registeredAt DESC")
    fun getAll(): LiveData<List<Registration>>

    @Query("SELECT * FROM registrations ORDER BY registeredAt DESC")
    suspend fun getAllSync(): List<Registration>

    @Query("SELECT * FROM registrations WHERE syncStatus = :status ORDER BY registeredAt ASC")
    suspend fun getByStatus(status: SyncStatus): List<Registration>

    @Query("SELECT * FROM registrations WHERE syncStatus = 'PENDING' OR syncStatus = 'FAILED'")
    suspend fun getUnsyncedRegistrations(): List<Registration>

    @Query("SELECT COUNT(*) FROM registrations WHERE syncStatus = 'PENDING' OR syncStatus = 'FAILED'")
    fun getUnsyncedCount(): LiveData<Int>

    @Query("SELECT * FROM registrations WHERE productCode LIKE '%' || :query || '%' OR constructionName LIKE '%' || :query || '%' ORDER BY registeredAt DESC")
    fun search(query: String): LiveData<List<Registration>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(registration: Registration): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(registrations: List<Registration>)

    @Update
    suspend fun update(registration: Registration)

    @Query("UPDATE registrations SET syncStatus = :status, syncedAt = :syncedAt WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, status: SyncStatus, syncedAt: Long? = null)

    @Delete
    suspend fun delete(registration: Registration)

    @Query("DELETE FROM registrations WHERE id = :id")
    suspend fun deleteById(id: Long)
}
