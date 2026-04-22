package com.crossvision.f.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ユーザー情報データモデル（ローカル認証用）
 * 実運用ではサーバー認証に置き換え
 */
@Entity(tableName = "users")
data class User(
    @PrimaryKey
    val userId: String,
    val password: String,     // ハッシュ化されたパスワード（本番ではサーバー認証を使用）
    val displayName: String = "",
    val isActive: Boolean = true
)
