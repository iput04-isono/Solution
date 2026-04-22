package com.example.mainproject.data.api

import com.example.mainproject.data.model.RegistrationRequest
import com.example.mainproject.data.model.RegistrationResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("/api/registrations") // TODO: サーバーURLが確定したらエンドポイントを更新
    suspend fun postRegistration(@Body request: RegistrationRequest): Response<RegistrationResponse>
}
