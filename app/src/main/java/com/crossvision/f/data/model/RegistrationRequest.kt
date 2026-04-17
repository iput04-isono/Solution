package com.crossvision.f.data.model

import com.google.gson.annotations.SerializedName

/**
 * サーバーへの登録リクエスト本文
 * Flask サーバーが受け取る JSON 形式に合わせる
 */
data class RegistrationRequest(
    @SerializedName("product_code")
    val productCode: String,

    @SerializedName("construction_name")
    val constructionName: String,

    @SerializedName("process_name")
    val processName: String,

    @SerializedName("user_id")
    val userId: String,

    @SerializedName("registered_at")
    val registeredAt: Long
)

/**
 * サーバーからのレスポンス
 */
data class RegistrationResponse(
    @SerializedName("success")
    val success: Boolean,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("id")
    val id: Int? = null
)
