package com.crossvision.f.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.crossvision.f.data.model.Process

@Dao
interface ProcessDao {

    @Query("SELECT * FROM processes WHERE constructionId = :constructionId AND isActive = 1 ORDER BY name ASC")
    fun getByConstructionId(constructionId: Long): LiveData<List<Process>>

    @Query("SELECT * FROM processes WHERE constructionId = :constructionId AND isActive = 1 ORDER BY name ASC")
    suspend fun getByConstructionIdSync(constructionId: Long): List<Process>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(process: Process): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(processes: List<Process>)

    @Delete
    suspend fun delete(process: Process)
}
