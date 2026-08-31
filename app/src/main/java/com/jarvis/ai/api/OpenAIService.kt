package com.jarvis.ai.api

import com.google.gson.Gson
import com.jarvis.ai.data.ChatCompletionRequest
import com.jarvis.ai.data.ChatCompletionResponse
import com.jarvis.ai.data.ChatMessage
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

    suspend fun testConnection(): Result<String> {
        val testRequest = ChatCompletionRequest(
            model = configManager.modelName.trim(),
            messages = listOf(ChatMessage("user", "Hello"))
        )
        return sendChatCompletion(testRequest).map { response ->
            val reply = response.choices?.firstOrNull()?.message?.content ?: "Connected successfully!"
            reply
        }
    }

    suspend fun sendChatCompletion(request: ChatCompletionRequest): Result<ChatCompletionResponse> {
        return try {
            var rawBaseUrl = configManager.baseUrl.trim()
            if (rawBaseUrl.isEmpty()) {
                return Result.failure(IOException("Base URL is empty! Please set it in Settings."))
            }
            if (!rawBaseUrl.startsWith("http://") && !rawBaseUrl.startsWith("https://")) {
                rawBaseUrl = "https://$rawBaseUrl"
            }
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
                if (endpoint.contains("generativelanguage.googleapis.com")) {
                    requestBuilder.addHeader("x-goog-api-key", apiKey)
                }
            }

            val callRequest = requestBuilder.build()
            val response = client.newCall(callRequest).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return Result.failure(IOException("API Error [HTTP ${response.code}]: $responseBody"))
            }

            val parsedResponse = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            Result.success(parsedResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
