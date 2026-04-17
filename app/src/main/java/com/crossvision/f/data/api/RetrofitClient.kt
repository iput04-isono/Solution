package com.crossvision.f.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Retrofit クライアントのシングルトン管理
 *
 * 【設定方法】
 *   アプリ起動前または設定画面で serverBaseUrl を PC の IPv4 アドレスに変更してください。
 *   例: RetrofitClient.serverBaseUrl = "http://192.168.1.10:5000"
 *
 * PC の IP アドレスは Windows なら PowerShell で `ipconfig` を実行して確認できます。
 * スマホと PC が同じ WiFi に接続されている必要があります。
 */
object RetrofitClient {

    /**
     * Flask サーバーのベース URL
     * 実機テスト時: PC の IPv4 アドレス（例: http://192.168.1.10:5000）
     * エミュレーター使用時: http://10.0.2.2:5000
     */
    var serverBaseUrl: String = "http://192.168.1.100:5000"
        set(value) {
            field = value
            // URL変更時にインスタンスをリセット
            _retrofit = null
            _apiService = null
        }

    private var _retrofit: Retrofit? = null
    private var _apiService: ApiService? = null

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient: OkHttpClient
        get() = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    private val retrofit: Retrofit
        get() = _retrofit ?: Retrofit.Builder()
            .baseUrl(serverBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .also { _retrofit = it }

    val apiService: ApiService
        get() = _apiService ?: retrofit.create(ApiService::class.java)
            .also { _apiService = it }
}
