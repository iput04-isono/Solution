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
    private val productLabelDao = db.productLabelDao()

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

    suspend fun replaceConstructions(constructions: List<Construction>) {
        constructionDao.deleteAll()
        constructionDao.insertAll(constructions)
    }

    suspend fun replaceProcesses(processes: List<Process>) {
        processDao.deleteAll()
        processDao.insertAll(processes)
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

    // ===== 製品コードマスター =====

    /** DB に保存されている製品コードを全件取得（LabelMatcher が使用） */
    suspend fun getAllProductLabels(): List<ProductLabel> =
        productLabelDao.getAll()

    /** DB の製品コード件数（0 件ならサーバー未同期） */
    suspend fun getProductLabelCount(): Int =
        productLabelDao.count()

    /** 最終同期日時（Unix ミリ秒）を取得 */
    suspend fun getProductLabelLastSyncedAt(): Long? =
        productLabelDao.lastSyncedAt()

    /**
     * サーバーから取得した製品コードリストで DB を更新する。
     * 既存データを全削除してから新しいリストを一括挿入する。
     */
    suspend fun replaceProductLabels(codes: List<String>) {
        val labels = codes
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { ProductLabel(code = it, updatedAt = System.currentTimeMillis()) }
        productLabelDao.deleteAll()
        productLabelDao.insertAll(labels)
    }
}
