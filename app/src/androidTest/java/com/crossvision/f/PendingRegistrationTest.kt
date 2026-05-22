package com.crossvision.f

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crossvision.f.data.local.AppDatabase
import com.crossvision.f.data.model.PendingRegistration
import com.crossvision.f.data.model.PendingRegistrationItem
import com.crossvision.f.data.model.SyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 複数製品の一括登録（親子構造）におけるデータベース操作、
 * およびカスケード削除（親削除時の連動削除）をテストするクラス。
 */
@RunWith(AndroidJUnit4::class)
class PendingRegistrationTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.getDatabase(context)
        // テスト前にデータベース内の既存の登録情報をクリア
        runBlocking {
            db.clearAllTables()
        }
    }

    @After
    fun tearDown() {
        // テスト後にもクリア
        runBlocking {
            db.clearAllTables()
        }
    }

    @Test
    fun testInsertAndGetRegistrationWithItems() = runBlocking {
        val dao = db.registrationDao()

        // 1. 親データ（共通メタデータ）を作成
        val parent = PendingRegistration(
            constructionName = "テストビル新築工事",
            processName = "柱加工",
            warehouseNo = "W-1",
            columnNo = "C-5",
            tierNo = "T-2",
            syncStatus = SyncStatus.PENDING,
            registeredAt = System.currentTimeMillis(),
            userId = "test_user_01"
        )

        // 2. 子データ（製品コードのリスト）を作成
        val items = listOf(
            PendingRegistrationItem(registrationId = 0, productCode = "PROD-TEST-A1", displayOrder = 0, registeredAt = System.currentTimeMillis()),
            PendingRegistrationItem(registrationId = 0, productCode = "PROD-TEST-A2", displayOrder = 1, registeredAt = System.currentTimeMillis()),
            PendingRegistrationItem(registrationId = 0, productCode = "PROD-TEST-A3", displayOrder = 2, registeredAt = System.currentTimeMillis())
        )

        // 3. 親子まとめてインサート
        val parentId = dao.insertRegistrationWithItems(parent, items)
        assertTrue("生成された親IDが有効（1以上）であること", parentId > 0)

        // 4. 親テーブルから保存したデータが取得できること
        val pendingRegistrations = dao.getPendingRegistrations()
        assertEquals("未同期の親レコードが1件取得できること", 1, pendingRegistrations.size)
        val savedParent = pendingRegistrations[0]
        assertEquals("テストビル新築工事", savedParent.constructionName)
        assertEquals("test_user_01", savedParent.userId)

        // 5. 親IDに紐づく子データが正しく取得できること
        val savedItems = dao.getItemsByRegistrationId(parentId)
        assertEquals("親IDに紐づく子レコードが3件取得できること", 3, savedItems.size)
        assertEquals("PROD-TEST-A1", savedItems[0].productCode)
        assertEquals("PROD-TEST-A2", savedItems[1].productCode)
        assertEquals("PROD-TEST-A3", savedItems[2].productCode)

        // 6. 互換レイヤーの getAllSync() からフラットなリストとして3件取得できること
        val flatList = dao.getAllSync()
        assertEquals("フラットな互換モデルとして3件のレコードが取得できること", 3, flatList.size)
        assertEquals("PROD-TEST-A1", flatList[0].productCode)
        assertEquals("テストビル新築工事", flatList[0].constructionName)
    }

    @Test
    fun testCascadeDelete() = runBlocking {
        val dao = db.registrationDao()

        val parent = PendingRegistration(
            constructionName = "カスケードテスト工事",
            processName = "梁加工",
            syncStatus = SyncStatus.PENDING,
            registeredAt = System.currentTimeMillis(),
            userId = "test_user_02"
        )
        val items = listOf(
            PendingRegistrationItem(registrationId = 0, productCode = "CASC-001", displayOrder = 0, registeredAt = System.currentTimeMillis()),
            PendingRegistrationItem(registrationId = 0, productCode = "CASC-002", displayOrder = 1, registeredAt = System.currentTimeMillis())
        )

        // インサートを実行
        val parentId = dao.insertRegistrationWithItems(parent, items)

        // データが存在することを確認
        assertEquals(1, dao.getPendingRegistrations().size)
        assertEquals(2, dao.getItemsByRegistrationId(parentId).size)

        // 親レコードを削除
        dao.deleteById(parentId)

        // 親が削除されたため、カスケード（連動）して子テーブルのデータも自動で削除されていることを確認
        assertEquals("親レコードが削除されていること", 0, dao.getPendingRegistrations().size)
        assertEquals("紐づいていた子レコードもカスケード削除されていること", 0, dao.getItemsByRegistrationId(parentId).size)
    }
}
