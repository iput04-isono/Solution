package com.crossvision.f.data.local

import androidx.room.*
import com.crossvision.f.data.model.User

@Dao
interface UserDao {

    @Query("SELECT * FROM users WHERE userId = :userId AND password = :password AND isActive = 1 LIMIT 1")
    suspend fun authenticate(userId: String, password: String): User?

    @Query("SELECT * FROM users WHERE userId = :userId LIMIT 1")
    suspend fun getById(userId: String): User?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(user: User)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(users: List<User>)
}
