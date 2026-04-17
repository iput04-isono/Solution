package com.crossvision.f.data.api

import com.crossvision.f.data.model.RegistrationRequest
import com.crossvision.f.data.model.RegistrationResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Flask サーバーとの通信エンドポイント定義
 */
interface ApiService {

    /**
     * OCR認識データをサーバーに登録する
     * POST /api/registrations
     */
    @POST("/api/registrations")
    suspend fun postRegistration(
        @Body request: RegistrationRequest
    ): Response<RegistrationResponse>
}
