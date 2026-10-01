package com.example.eva.ai

import android.util.Log
import com.example.eva.data.prefs.AiProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class OpenAiCompatibleProvider(
    override val providerType: AiProviderType,
    private val getBaseUrl: () -> String,
    private val getApiKey: () -> String,
    private val getModel: () -> String,
    private val getTemperature: () -> Float = { 0.7f },
    private val getMaxTokens: () -> Int = { 2048 },
    private val getSendSystemPrompt: () -> Boolean = { true },
    private val extraHeaders: Map<String, String> = emptyMap()
) : AiProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun generateResponse(
        messages: List<ChatMessage>,
        toolsPrompt: String?,
        temperatureOverride: Float?,
        maxTokensOverride: Int?
    ): AiResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val baseUrl = getBaseUrl().trim().trimEnd('/')
        val apiKey = getApiKey().trim()
        val model = getModel().trim()

        val endpoint = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            "$baseUrl/chat/completions"
        }

        val requestJson = JSONObject()
        requestJson.put("model", model)

        val messagesArray = JSONArray()
        if (getSendSystemPrompt() && !toolsPrompt.isNullOrBlank()) {
            val systemObj = JSONObject()
            systemObj.put("role", "system")
            systemObj.put("content", toolsPrompt)
            messagesArray.put(systemObj)
        }

        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            if (msg.imageBase64 != null) {
                val parts = JSONArray()
                parts.put(JSONObject().apply {
                    put("type", "text")
                    put("text", msg.content)
                })
                parts.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                    })
                })
                msgObj.put("content", parts)
            } else {
                msgObj.put("content", msg.content)
            }
            messagesArray.put(msgObj)
        }
        requestJson.put("messages", messagesArray)
        requestJson.put("temperature", temperatureOverride ?: getTemperature())
        requestJson.put("max_tokens", maxTokensOverride ?: getMaxTokens())

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody(jsonMediaType))

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        for ((k, v) in extraHeaders) {
            requestBuilder.addHeader(k, v)
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val latency = System.currentTimeMillis() - startTime
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorDetails = parseErrorBody(responseBody, response.code)
                return@withContext AiResponse(
                    content = "Server returned error ($errorDetails)",
                    providerUsed = providerType,
                    latencyMs = latency,
                    isSuccess = false,
                    errorMessage = errorDetails
                )
            }

            val json = JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            val firstChoice = choices?.optJSONObject(0)
            val messageObj = firstChoice?.optJSONObject("message")
            val content = messageObj?.optString("content") ?: ""

            val toolCall = extractToolCall(content)
            val cleanContent = cleanContentOfToolJson(content)

            AiResponse(
                content = cleanContent.ifBlank { if (toolCall != null) "On it." else "I'm right here. How can I help you?" },
                toolCall = toolCall,
                providerUsed = providerType,
                latencyMs = latency,
                isSuccess = true
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            AiResponse(
                content = "Could not reach ${providerType.displayName}: ${e.localizedMessage ?: "Connection error"}",
                providerUsed = providerType,
                latencyMs = latency,
                isSuccess = false,
                errorMessage = e.localizedMessage
            )
        }
    }

    /**
     * Streams responses using Server-Sent Events (SSE / "stream": true).
     */
    suspend fun generateStreamingResponse(
        messages: List<ChatMessage>,
        toolsPrompt: String?,
        onToken: (String) -> Unit
    ): AiResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val baseUrl = getBaseUrl().trim().trimEnd('/')
        val apiKey = getApiKey().trim()
        val model = getModel().trim()

        val endpoint = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            "$baseUrl/chat/completions"
        }

        val requestJson = JSONObject()
        requestJson.put("model", model)
        requestJson.put("stream", true)

        val messagesArray = JSONArray()
        if (getSendSystemPrompt() && !toolsPrompt.isNullOrBlank()) {
            val systemObj = JSONObject()
            systemObj.put("role", "system")
            systemObj.put("content", toolsPrompt)
            messagesArray.put(systemObj)
        }

        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            if (msg.imageBase64 != null) {
                val parts = JSONArray()
                parts.put(JSONObject().apply {
                    put("type", "text")
                    put("text", msg.content)
                })
                parts.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                    })
                })
                msgObj.put("content", parts)
            } else {
                msgObj.put("content", msg.content)
            }
            messagesArray.put(msgObj)
        }
        requestJson.put("messages", messagesArray)
        requestJson.put("temperature", getTemperature())
        requestJson.put("max_tokens", getMaxTokens())

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody(jsonMediaType))

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        for ((k, v) in extraHeaders) {
            requestBuilder.addHeader(k, v)
        }

        val accumulatedContent = StringBuilder()

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val latency = System.currentTimeMillis() - startTime

            if (!response.isSuccessful) {
                val errorDetails = parseErrorBody(response.body?.string() ?: "", response.code)
                return@withContext AiResponse(
                    content = "Server returned error ($errorDetails)",
                    providerUsed = providerType,
                    latencyMs = latency,
                    isSuccess = false,
                    errorMessage = errorDetails
                )
            }

            val body = response.body ?: return@withContext AiResponse(
                content = "Empty response body",
                providerUsed = providerType,
                isSuccess = false
            )

            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("data:") && !trimmed.contains("[DONE]")) {
                    val jsonChunk = trimmed.removePrefix("data:").trim()
                    try {
                        val chunkObj = JSONObject(jsonChunk)
                        val choices = chunkObj.optJSONArray("choices")
                        val delta = choices?.optJSONObject(0)?.optJSONObject("delta")
                        val piece = delta?.optString("content") ?: ""
                        if (piece.isNotEmpty()) {
                            accumulatedContent.append(piece)
                            onToken(piece)
                        }
                    } catch (_: Exception) {}
                }
                line = reader.readLine()
            }

            val fullContent = accumulatedContent.toString()
            val toolCall = extractToolCall(fullContent)
            val cleanContent = cleanContentOfToolJson(fullContent)

            AiResponse(
                content = cleanContent.ifBlank { if (toolCall != null) "On it." else "I'm right here. How can I help you?" },
                toolCall = toolCall,
                providerUsed = providerType,
                latencyMs = System.currentTimeMillis() - startTime,
                isSuccess = true
            )
        } catch (e: Exception) {
            AiResponse(
                content = "Streaming error from ${providerType.displayName}: ${e.localizedMessage ?: "Unknown error"}",
                providerUsed = providerType,
                latencyMs = System.currentTimeMillis() - startTime,
                isSuccess = false,
                errorMessage = e.localizedMessage
            )
        }
    }

    /**
     * Queries /v1/models or /models endpoint to fetch supported models.
     */
    suspend fun fetchAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl().trim().trimEnd('/')
        val apiKey = getApiKey().trim()

        val modelsUrl = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl.removeSuffix("/chat/completions") + "/models"
        } else if (baseUrl.endsWith("/v1")) {
            "$baseUrl/models"
        } else {
            "$baseUrl/v1/models"
        }

        val requestBuilder = Request.Builder().url(modelsUrl).get()
        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) return@withContext emptyList()

            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i)
                val id = item?.optString("id")
                if (!id.isNullOrBlank()) {
                    list.add(id)
                }
            }
            list.sorted()
        } catch (e: Exception) {
            Log.w("OpenAiCompatibleProvider", "Could not fetch models: ${e.message}")
            emptyList()
        }
    }

    override suspend fun testConnection(): ProviderTestResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val baseUrl = getBaseUrl().trim().trimEnd('/')
        val apiKey = getApiKey().trim()
        val model = getModel().trim()

        val endpoint = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            "$baseUrl/chat/completions"
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                })
            })
            put("max_tokens", 5)
        }

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody(jsonMediaType))

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        for ((k, v) in extraHeaders) {
            requestBuilder.addHeader(k, v)
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val latency = System.currentTimeMillis() - startTime
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                ProviderTestResult(
                    isSuccess = true,
                    message = "Connected successfully (${latency}ms)",
                    latencyMs = latency,
                    httpCode = response.code
                )
            } else {
                val err = parseErrorBody(body, response.code)
                ProviderTestResult(
                    isSuccess = false,
                    message = "Error $err",
                    latencyMs = latency,
                    httpCode = response.code
                )
            }
        } catch (e: Exception) {
            ProviderTestResult(
                isSuccess = false,
                message = "Connection failed: ${e.localizedMessage ?: "Unknown error"}",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun parseErrorBody(body: String, code: Int): String {
        return try {
            val json = JSONObject(body)
            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                if (errObj != null) {
                    errObj.optString("message", "HTTP $code")
                } else {
                    json.optString("error", "HTTP $code")
                }
            } else {
                "HTTP $code: ${body.take(120)}"
            }
        } catch (_: Exception) {
            "HTTP $code"
        }
    }

    private fun extractToolCall(content: String): AiToolCall? {
        return try {
            val jsonText = if (content.contains("```json")) {
                val start = content.indexOf("```json") + 7
                val end = content.indexOf("```", start)
                if (end != -1) content.substring(start, end).trim() else content.substring(start).trim()
            } else if (content.contains("```")) {
                val start = content.indexOf("```") + 3
                val end = content.indexOf("```", start)
                if (end != -1) content.substring(start, end).trim() else content.substring(start).trim()
            } else {
                val match = Regex("""\{[\s\S]*?"tool"\s*:[\s\S]*?\}""").find(content)
                match?.value ?: ""
            }

            if (jsonText.isNotBlank()) {
                val obj = JSONObject(jsonText)
                if (obj.has("tool")) {
                    val tool = obj.getString("tool")
                    val action = obj.optString("action", "execute")
                    val params = mutableMapOf<String, String>()
                    val paramsObj = obj.optJSONObject("parameters")
                    paramsObj?.keys()?.forEach { k ->
                        params[k] = paramsObj.optString(k, "")
                    }
                    AiToolCall(toolName = tool, action = action, parameters = params)
                } else null
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanContentOfToolJson(content: String): String {
        var clean = content
        val jsonStart = clean.indexOf("```json")
        if (jsonStart != -1) {
            val jsonEnd = clean.indexOf("```", jsonStart + 7)
            if (jsonEnd != -1) {
                clean = clean.removeRange(jsonStart, jsonEnd + 3).trim()
            }
        }
        val braceStart = clean.indexOf("{\"tool\":")
        if (braceStart != -1) {
            val braceEnd = clean.lastIndexOf("}")
            if (braceEnd > braceStart) {
                clean = clean.removeRange(braceStart, braceEnd + 1).trim()
            }
        }
        return clean
    }
}
