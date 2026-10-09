package com.example.calltracker.di

import com.example.calltracker.BuildConfig
import com.example.calltracker.data.remote.CrmApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${BuildConfig.CRM_API_TOKEN}").build())
        }
        .apply {
            if (BuildConfig.DEBUG) {
                // Request lines only: bodies carry names and numbers. The lookup's phone query value is redacted.
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.BASIC
                        redactQueryParams("phone")
                    },
                )
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideCrmApi(client: OkHttpClient, json: Json): CrmApi = Retrofit.Builder()
        .baseUrl(BuildConfig.CRM_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json; charset=UTF-8".toMediaType()))
        .build()
        .create(CrmApi::class.java)
}
