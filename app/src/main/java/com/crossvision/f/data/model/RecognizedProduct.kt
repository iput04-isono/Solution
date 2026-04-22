package com.crossvision.f.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 製品コード認識結果のデータモデル
 * OCRで読み取った製品コードの情報を保持
 */
@Entity(tableName = "recognized_products")
data class RecognizedProduct(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val productCode: String,           // 製品コード
    val originalText: String = "",     // OCR生テキスト（修正前）
    val confidence: Float = 0f,        // 認識信頼度（0.0〜1.0）
    val isEdited: Boolean = false,     // ユーザーが手動修正したか
    val boundingBoxLeft: Float = 0f,   // 認識領域（左座標）
    val boundingBoxTop: Float = 0f,    // 認識領域（上座標）
    val boundingBoxRight: Float = 0f,  // 認識領域（右座標）
    val boundingBoxBottom: Float = 0f, // 認識領域（下座標）
    val sessionId: String = "",        // 撮影セッションID（同じ撮影の製品をグループ化）
    val createdAt: Long = System.currentTimeMillis()
)
