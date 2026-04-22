package com.crossvision.f.data.repository

import android.content.Context
import androidx.lifecycle.LiveData
import com.crossvision.f.data.local.AppDatabase
import com.crossvision.f.data.model.*

/**
 * データアクセスを一元管理するリポジトリ
 * ViewModelからはこのクラスを通じてデータにアクセスする
 */
class AppRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val constructionDao = db.constructionDao()
    private val processDao = db.processDao()
    private val registrationDao = db.registrationDao()
    private val userDao = db.userDao()

    // ===== ユーザー認証 =====

    suspend fun authenticate(userId: String, password: String): User? {
        return userDao.authenticate(userId, password)
    }

    // ===== 工事・工程 =====

    fun getAllConstructions(): LiveData<List<Construction>> {
        return constructionDao.getAllActive()
    }

    suspend fun getAllConstructionsSync(): List<Construction> {
        return constructionDao.getAllActiveSync()
    }

    fun getProcessesByConstructionId(constructionId: Long): LiveData<List<Process>> {
        return processDao.getByConstructionId(constructionId)
    }

    suspend fun getProcessesByConstructionIdSync(constructionId: Long): List<Process> {
        return processDao.getByConstructionIdSync(constructionId)
    }

    // ===== 登録 =====

    fun getAllRegistrations(): LiveData<List<Registration>> {
        return registrationDao.getAll()
    }

    suspend fun insertRegistration(registration: Registration): Long {
        return registrationDao.insert(registration)
    }

    suspend fun insertRegistrations(registrations: List<Registration>) {
        registrationDao.insertAll(registrations)
    }

    suspend fun updateRegistration(registration: Registration) {
        registrationDao.update(registration)
    }

    suspend fun deleteRegistration(id: Long) {
        registrationDao.deleteById(id)
    }

    suspend fun getUnsyncedRegistrations(): List<Registration> {
        return registrationDao.getUnsyncedRegistrations()
    }

    fun getUnsyncedCount(): LiveData<Int> {
        return registrationDao.getUnsyncedCount()
    }

    suspend fun updateSyncStatus(id: Long, status: SyncStatus, syncedAt: Long? = null) {
        registrationDao.updateSyncStatus(id, status, syncedAt)
    }

    fun searchRegistrations(query: String): LiveData<List<Registration>> {
        return registrationDao.search(query)
    }
}
