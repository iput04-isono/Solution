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
        PendingRegistration::class,
        PendingRegistrationItem::class,
        User::class,
        ProductLabel::class
    ],
    version = 3,
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

        /**
         * v2 → v3 マイグレーション
         * registrations テーブルを pending_registrations と pending_registration_items の親子構造に分割する
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. pending_registrations テーブルの作成
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_registrations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        constructionName TEXT NOT NULL,
                        processName TEXT NOT NULL,
                        warehouseNo TEXT NOT NULL DEFAULT '',
                        columnNo TEXT NOT NULL DEFAULT '',
                        tierNo TEXT NOT NULL DEFAULT '',
                        syncStatus TEXT NOT NULL,
                        registeredAt INTEGER NOT NULL,
                        syncedAt INTEGER,
                        userId TEXT NOT NULL DEFAULT '',
                        imagePath TEXT,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        errorMessage TEXT
                    )
                    """.trimIndent()
                )

                // 2. pending_registration_items テーブルの作成
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_registration_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        registrationId INTEGER NOT NULL,
                        productCode TEXT NOT NULL,
                        displayOrder INTEGER NOT NULL,
                        registeredAt INTEGER NOT NULL,
                        FOREIGN KEY(registrationId) REFERENCES pending_registrations(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                // インデックスの作成
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_registration_items_registrationId ON pending_registration_items (registrationId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_registration_items_productCode ON pending_registration_items (productCode)")

                // 3. データの移行（既存の registrations テーブルが存在する場合のみ）
                val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='registrations'")
                if (cursor.moveToFirst()) {
                    // 親テーブルへ移行
                    db.execSQL(
                        """
                        INSERT INTO pending_registrations (id, constructionName, processName, warehouseNo, columnNo, tierNo, syncStatus, registeredAt, syncedAt, userId, imagePath, retryCount, errorMessage)
                        SELECT id, constructionName, processName, warehouseNo, columnNo, tierNo, syncStatus, registeredAt, syncedAt, userId, imagePath, 0, NULL FROM registrations
                        """.trimIndent()
                    )
                    // 子テーブルへ移行
                    db.execSQL(
                        """
                        INSERT INTO pending_registration_items (registrationId, productCode, displayOrder, registeredAt)
                        SELECT id, productCode, 0, registeredAt FROM registrations
                        """.trimIndent()
                    )
                    // 旧テーブルの削除
                    db.execSQL("DROP TABLE IF EXISTS registrations")
                }
                cursor.close()
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "crossvision_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
            // SQLを直接実行して同期的にサンプルデータを投入する。
            // これにより、非同期実行によるテストコード（clearAllTables）との競合を防ぐ。
            db.beginTransaction()
            try {
                // テスト用ユーザー
                db.execSQL("INSERT INTO users (userId, password, displayName, isActive) VALUES ('admin', 'admin123', '管理者', 1)")
                db.execSQL("INSERT INTO users (userId, password, displayName, isActive) VALUES ('user01', 'pass01', '作業員01', 1)")
                db.execSQL("INSERT INTO users (userId, password, displayName, isActive) VALUES ('user02', 'pass02', '作業員02', 1)")

                // サンプル工事データ
                db.execSQL("INSERT INTO constructions (id, name, code, isActive) VALUES (1, '○○ビル新築工事', 'CONST-001', 1)")
                db.execSQL("INSERT INTO constructions (id, name, code, isActive) VALUES (2, '△△マンション改修工事', 'CONST-002', 1)")
                db.execSQL("INSERT INTO constructions (id, name, code, isActive) VALUES (3, '□□工場増築工事', 'CONST-003', 1)")

                // サンプル工程データ（工事に紐づく）
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (1, 1, '柱加工', 'PROC-001', 1)")
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (2, 1, '梁加工', 'PROC-002', 1)")
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (3, 1, '検査', 'PROC-003', 1)")
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (4, 1, '出荷', 'PROC-004', 1)")
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (5, 2, '搬入', 'PROC-005', 1)")
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (6, 2, '組立', 'PROC-006', 1)")
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (7, 2, '溶接', 'PROC-007', 1)")
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (8, 3, '切断', 'PROC-008', 1)")
                db.execSQL("INSERT INTO processes (id, constructionId, name, code, isActive) VALUES (9, 3, '塗装', 'PROC-009', 1)")

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }
}

