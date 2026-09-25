package com.example.eva.ai

import com.example.eva.data.prefs.AiProviderType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenAiCompatibleProvider(
    override val providerType: AiProviderType,
    private val getBaseUrl: () -> String,
    private val getApiKey: () -> String,
    private val getModel: () -> String,
    private val extraHeaders: Map<String, String> = emptyMap()
) : AiProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun generateResponse(
        messages: List<ChatMessage>,
        toolsPrompt: String?
    ): AiResponse {
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
        if (!toolsPrompt.isNullOrBlank()) {
            val systemObj = JSONObject()
            systemObj.put("role", "system")
            systemObj.put("content", toolsPrompt)
            messagesArray.put(systemObj)
        }

        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            messagesArray.put(msgObj)
        }
        requestJson.put("messages", messagesArray)
        requestJson.put("temperature", 0.7)

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody(jsonMediaType))

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        for ((k, v) in extraHeaders) {
            requestBuilder.addHeader(k, v)
        }

        return try {
            val response = client.newCall(requestBuilder.build()).execute()
            val latency = System.currentTimeMillis() - startTime
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorDetails = parseErrorBody(responseBody, response.code)
                return AiResponse(
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
                content = cleanContent.ifBlank { "Done." },
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

    override suspend fun testConnection(): ProviderTestResult {
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

        return try {
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
            val jsonStart = content.indexOf("```json")
            val jsonText = if (jsonStart != -1) {
                val actualStart = jsonStart + 7
                val jsonEnd = content.indexOf("```", actualStart)
                if (jsonEnd != -1) content.substring(actualStart, jsonEnd).trim() else ""
            } else {
                val braceStart = content.indexOf("{\"tool\":")
                if (braceStart != -1) {
                    val braceEnd = content.lastIndexOf("}")
                    if (braceEnd > braceStart) content.substring(braceStart, braceEnd + 1) else ""
                } else ""
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
