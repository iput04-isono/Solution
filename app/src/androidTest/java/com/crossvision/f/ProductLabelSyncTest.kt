package com.crossvision.f

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.crossvision.f.data.local.AppDatabase
import com.crossvision.f.data.model.ProductLabel
import com.crossvision.f.data.repository.AppRepository
import com.crossvision.f.ocr.LabelMatcher
import com.crossvision.f.sync.SyncManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 製品コードマスターの DB 管理・サーバー同期を実機で自動テストするクラス。
 *
 * 実行方法:
 *   Android Studio: テストクラスを右クリック → "Run ProductLabelSyncTest"
 *   コマンドライン:
 *     .\gradlew.bat connectedDebugAndroidTest `
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.crossvision.f.ProductLabelSyncTest
 */
@RunWith(AndroidJUnit4::class)
class ProductLabelSyncTest {

    private lateinit var context: Context
    private lateinit var repository: AppRepository
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context    = InstrumentationRegistry.getInstrumentation().targetContext
        db         = AppDatabase.getDatabase(context)
        repository = AppRepository(context)
    }

    @After
    fun tearDown() {
        // テスト後に product_labels をクリアして他のテストに影響しないようにする
        runBlocking { repository.replaceProductLabels(emptyList()) }
    }

    // ──────────────────────────────────────────────────────────────────────
    // テスト 1: DB が空のとき assets からフォールバック読み込みできること
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun test1_labelMatcher_usesAssetsWhenDbEmpty() = runBlocking {
        // DB を空にする
        repository.replaceProductLabels(emptyList())
        assertEquals("DB は空であること", 0, repository.getProductLabelCount())

        // LabelMatcher.create() は assets からフォールバックするはず
        val matcher = LabelMatcher.create(context)
        assertTrue("assets から 1 件以上読み込まれること", matcher.labels.isNotEmpty())

        println("[TEST 1 OK] assets フォールバック: ${matcher.labels.size}件")
    }

    // ──────────────────────────────────────────────────────────────────────
    // テスト 2: DB にデータがあるとき DB から読み込むこと
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun test2_labelMatcher_usesDbWhenAvailable() = runBlocking {
        // テスト用コードを DB に保存
        val testCodes = listOf("TEST-001", "TEST-002", "TEST-003")
        repository.replaceProductLabels(testCodes)
        assertEquals("DB に 3 件入っていること", 3, repository.getProductLabelCount())

        // LabelMatcher.create() は DB から読み込むはず
        val matcher = LabelMatcher.create(context)
        assertEquals("DB の 3 件が使われること", 3, matcher.labels.size)
        assertTrue("TEST-001 が含まれること", matcher.labels.contains("TEST-001"))

        println("[TEST 2 OK] DB 読み込み: ${matcher.labels.size}件")
    }

    // ──────────────────────────────────────────────────────────────────────
    // テスト 3: サーバー同期（assets モック）後に DB が更新されること
    // ※ ネットワーク不要: assets を「サーバーレスポンス」として使用
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun test3_syncUpdatesDb() = runBlocking {
        // DB を空にして同期前の状態にする
        repository.replaceProductLabels(emptyList())
        assertEquals("同期前は DB が空", 0, repository.getProductLabelCount())

        // assets から製品コードを読み込んで DB に保存（サーバー同期をシミュレート）
        val codesFromAssets = context.assets.open("product_labels.txt")
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.all { c -> c.code < 128 } }
            .distinct()
        repository.replaceProductLabels(codesFromAssets)

        assertTrue("同期で 1 件以上保存されること", codesFromAssets.isNotEmpty())
        assertEquals("DB の件数が同期件数と一致すること",
            codesFromAssets.size, repository.getProductLabelCount())

        println("[TEST 3 OK] 同期後 DB 件数: ${repository.getProductLabelCount()}件")
    }

    // ──────────────────────────────────────────────────────────────────────
    // テスト 4: DB 更新後に LabelMatcher が新しいコードで照合できること
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun test4_labelMatcherReflectsUpdatedDb() = runBlocking {
        // 旧コードで DB を初期化
        repository.replaceProductLabels(listOf("OLD-CODE-001", "OLD-CODE-002"))

        // 新コードに更新（サーバー同期をシミュレート）
        val newCodes = listOf("NEW-STEEL-A1", "NEW-STEEL-B2", "NEW-STEEL-C3")
        repository.replaceProductLabels(newCodes)

        // 更新後の LabelMatcher が新コードで照合できること
        val matcher = LabelMatcher.create(context)
        assertEquals("新コード 3 件が反映されること", 3, matcher.labels.size)
        assertFalse("旧コードは含まれないこと", matcher.labels.contains("OLD-CODE-001"))
        assertTrue("新コードが含まれること", matcher.labels.contains("NEW-STEEL-A1"))

        // ファジーマッチングも動作すること（1文字違い）
        val result = matcher.findBest("NEW-STEEL-A2")  // A1 と 1 文字違い
        assertNotNull("1文字違いでもマッチすること", result)
        assertEquals("正解ラベルが返ること", "NEW-STEEL-A1", result?.label)
        assertEquals("編集距離が 1 であること", 1, result?.distance)

        println("[TEST 4 OK] 更新後照合: ${result?.label} (距離=${result?.distance})")
    }

    // ──────────────────────────────────────────────────────────────────────
    // テスト 5: 24時間以内の再同期がスキップされること
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun test5_syncSkipsIfRecentlySynced() = runBlocking {
        // 直前に同期済みのデータを DB に入れる（updatedAt = now）
        val recentLabels = listOf("RECENT-001").map {
            ProductLabel(code = it, updatedAt = System.currentTimeMillis())
        }
        db.productLabelDao().deleteAll()
        db.productLabelDao().insertAll(recentLabels)

        // 同期を試みると 24 時間以内としてスキップされるはず（-1 が返る）
        val syncManager = SyncManager(context)
        val result = syncManager.syncProductLabels()
        assertEquals("24時間以内の同期はスキップ（-1）", -1, result)

        println("[TEST 5 OK] 24時間以内の再同期スキップ確認")
    }
}
