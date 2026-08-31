package com.jarvis.ai.api

import com.google.gson.Gson
import com.jarvis.ai.data.ChatCompletionRequest
import com.jarvis.ai.data.ChatCompletionResponse
import com.jarvis.ai.data.ConfigManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAIService(private val configManager: ConfigManager) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun sendChatCompletion(request: ChatCompletionRequest): Result<ChatCompletionResponse> {
        return try {
            val rawBaseUrl = configManager.baseUrl.trim()
            val cleanBaseUrl = if (rawBaseUrl.endsWith("/")) rawBaseUrl else "$rawBaseUrl/"
            val endpoint = "${cleanBaseUrl}chat/completions"

            val jsonBody = gson.toJson(request)
            val requestBody = jsonBody.toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")

            val apiKey = configManager.apiKey.trim()
            if (apiKey.isNotEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val callRequest = requestBuilder.build()

            val response = client.newCall(callRequest).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(IOException("API Error [${response.code}]: $responseBody"))
            }

            val parsedResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            Result.success(parsedResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
