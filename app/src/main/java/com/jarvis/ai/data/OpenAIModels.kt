package com.jarvis.ai.data

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

data class ChatCompletionRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<ChatMessage>,
    @SerializedName("temperature") val temperature: Double = 0.7
)

data class ChatChoice(
    @SerializedName("index") val index: Int,
    @SerializedName("message") val message: ChatMessage,
    @SerializedName("finish_reason") val finishReason: String?
)

data class ChatCompletionResponse(
    @SerializedName("id") val id: String?,
    @SerializedName("choices") val choices: List<ChatChoice>?,
    @SerializedName("error") val error: ApiError?
)

data class ApiError(
    @SerializedName("message") val message: String?,
    @SerializedName("type") val type: String?,
    @SerializedName("code") val code: String?
)

data class JarvisActionCommand(
    @SerializedName("action") val action: String?,
    @SerializedName("data") val data: Map<String, String>?,
    @SerializedName("speech_response") val speechResponse: String?
)
