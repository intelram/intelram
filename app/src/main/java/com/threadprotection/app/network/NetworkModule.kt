package com.threadprotection.app.network

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.threadprotection.app.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP plumbing for every free threat-intel source in README §Threat intelligence
 * (Google Safe Browsing, VirusTotal, AbuseIPDB, NVD, URLhaus, ThreatFox, PhishTank). One
 * lenient JSON instance and one Retrofit-per-base-URL factory, all sharing a single OkHttp
 * client and connection pool.
 */
object NetworkModule {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
                }
            }
            .build()
    }

    private val converterFactory = json.asConverterFactory("application/json".toMediaType())

    fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(converterFactory)
        .build()

    inline fun <reified T> create(baseUrl: String): T = retrofit(baseUrl).create(T::class.java)
}
