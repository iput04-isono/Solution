package com.crossvision.f.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.crossvision.f.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * アプリのRoom Database
 * シングルトンパターンでインスタンスを管理
 *
 * バージョン履歴:
 *   v1 → v2: product_labels テーブルを追加（製品コードマスターのDB管理化）
 */
@Database(
    entities = [
        Construction::class,
        Process::class,
        RecognizedProduct::class,
        Registration::class,
        User::class,
        ProductLabel::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun constructionDao(): ConstructionDao
    abstract fun processDao(): ProcessDao
    abstract fun registrationDao(): RegistrationDao
    abstract fun userDao(): UserDao
    abstract fun productLabelDao(): ProductLabelDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2 マイグレーション
         * product_labels テーブルを追加する
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS product_labels (
                        code TEXT NOT NULL PRIMARY KEY,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "crossvision_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(DatabaseCallback())
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    /**
     * 初回DB作成時にサンプルデータを投入するコールバック
     */
    private class DatabaseCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            INSTANCE?.let { database ->
                CoroutineScope(Dispatchers.IO).launch {
                    populateDatabase(database)
                }
            }
        }
    }
}

/**
 * サンプルデータの投入
 * 実運用ではサーバーからマスターデータを取得する
 */
private suspend fun populateDatabase(db: AppDatabase) {
    // テスト用ユーザー
    db.userDao().insertAll(
        listOf(
            User("admin", "admin123", "管理者"),
            User("user01", "pass01", "作業員01"),
            User("user02", "pass02", "作業員02")
        )
    )

    // サンプル工事データ
    val construction1Id = db.constructionDao().insert(
        Construction(name = "○○ビル新築工事", code = "CONST-001")
    )
    val construction2Id = db.constructionDao().insert(
        Construction(name = "△△マンション改修工事", code = "CONST-002")
    )
    val construction3Id = db.constructionDao().insert(
        Construction(name = "□□工場増築工事", code = "CONST-003")
    )

    // サンプル工程データ（工事に紐づく）
    db.processDao().insertAll(
        listOf(
            Process(constructionId = construction1Id, name = "柱加工", code = "PROC-001"),
            Process(constructionId = construction1Id, name = "梁加工", code = "PROC-002"),
            Process(constructionId = construction1Id, name = "検査", code = "PROC-003"),
            Process(constructionId = construction1Id, name = "出荷", code = "PROC-004"),
            Process(constructionId = construction2Id, name = "搬入", code = "PROC-005"),
            Process(constructionId = construction2Id, name = "組立", code = "PROC-006"),
            Process(constructionId = construction2Id, name = "溶接", code = "PROC-007"),
            Process(constructionId = construction3Id, name = "切断", code = "PROC-008"),
            Process(constructionId = construction3Id, name = "塗装", code = "PROC-009")
        )
    )
}
