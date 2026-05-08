package com.crossvision.f.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.crossvision.f.data.model.Construction

@Dao
interface ConstructionDao {

    @Query("SELECT * FROM constructions WHERE isActive = 1 ORDER BY name ASC")
    fun getAllActive(): LiveData<List<Construction>>

    @Query("SELECT * FROM constructions WHERE isActive = 1 ORDER BY name ASC")
    suspend fun getAllActiveSync(): List<Construction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(construction: Construction): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(constructions: List<Construction>)

    @Delete
    suspend fun delete(construction: Construction)

    @Query("DELETE FROM constructions")
    suspend fun deleteAll()
}
