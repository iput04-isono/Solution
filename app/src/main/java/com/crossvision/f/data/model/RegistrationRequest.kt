package com.crossvision.f.data.model

import com.google.gson.annotations.SerializedName

/**
 * サーバーへの登録リクエスト本文
 * Flask サーバーが受け取る JSON 形式に合わせる
 */
data class RegistrationRequest(
    @SerializedName("process_id")
    val processId: Int,

    @SerializedName("division")
    val division: String,

    @SerializedName("worker_id")
    val workerId: Int,

    @SerializedName("device_id")
    val deviceId: String,

    @SerializedName("registered_at")
    val registeredAt: String,

    @SerializedName("product_numbers")
    val productNumbers: List<String>
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
