package com.example.eva.ai

import com.example.eva.data.prefs.AiProviderType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiProvider(
    private val getApiKey: () -> String,
    private val getModel: () -> String
) : AiProvider {

    override val providerType: AiProviderType = AiProviderType.GEMINI

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun generateResponse(
        messages: List<ChatMessage>,
        toolsPrompt: String?
    ): AiResponse {
        val startTime = System.currentTimeMillis()
        val apiKey = getApiKey().trim()
        val model = getModel().trim().ifBlank { "gemini-2.5-flash" }

        if (apiKey.isBlank()) {
            return AiResponse(
                content = "Google Gemini API key is missing. Please enter your API key in Settings.",
                providerUsed = providerType,
                latencyMs = 0L,
                isSuccess = false,
                errorMessage = "API key not configured"
            )
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val requestJson = JSONObject()
        val contentsArray = JSONArray()

        if (!toolsPrompt.isNullOrBlank()) {
            val systemInstruction = JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", toolsPrompt) })
                })
            }
            requestJson.put("systemInstruction", systemInstruction)
        }

        for (msg in messages) {
            val role = if (msg.role == "assistant") "model" else "user"
            val contentObj = JSONObject().apply {
                put("role", role)
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", msg.content) })
                })
            }
            contentsArray.put(contentObj)
        }
        requestJson.put("contents", contentsArray)

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val latency = System.currentTimeMillis() - startTime
            val body = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val err = parseError(body, response.code)
                return AiResponse(
                    content = "Gemini returned error ($err)",
                    providerUsed = providerType,
                    latencyMs = latency,
                    isSuccess = false,
                    errorMessage = err
                )
            }

            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val rawText = parts?.optJSONObject(0)?.optString("text") ?: ""

            val toolCall = extractToolCall(rawText)
            val clean = cleanContent(rawText)

            AiResponse(
                content = clean.ifBlank { "Done." },
                toolCall = toolCall,
                providerUsed = providerType,
                latencyMs = latency,
                isSuccess = true
            )
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            AiResponse(
                content = "Could not connect to Gemini: ${e.localizedMessage ?: "Unknown error"}",
                providerUsed = providerType,
                latencyMs = latency,
                isSuccess = false,
                errorMessage = e.localizedMessage
            )
        }
    }

    override suspend fun testConnection(): ProviderTestResult {
        val startTime = System.currentTimeMillis()
        val apiKey = getApiKey().trim()
        val model = getModel().trim().ifBlank { "gemini-2.5-flash" }

        if (apiKey.isBlank()) {
            return ProviderTestResult(
                isSuccess = false,
                message = "API key is not configured"
            )
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "ping") })
                    })
                })
            })
        }

        return try {
            val req = Request.Builder()
                .url(url)
                .post(requestJson.toString().toRequestBody(jsonMediaType))
                .build()

            val response = client.newCall(req).execute()
            val latency = System.currentTimeMillis() - startTime
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                ProviderTestResult(
                    isSuccess = true,
                    message = "Gemini connected successfully (${latency}ms)",
                    latencyMs = latency,
                    httpCode = response.code
                )
            } else {
                val err = parseError(body, response.code)
                ProviderTestResult(
                    isSuccess = false,
                    message = "Gemini error: $err",
                    latencyMs = latency,
                    httpCode = response.code
                )
            }
        } catch (e: Exception) {
            ProviderTestResult(
                isSuccess = false,
                message = "Gemini connection failed: ${e.localizedMessage}",
                latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun parseError(body: String, code: Int): String {
        return try {
            val json = JSONObject(body)
            val errObj = json.optJSONObject("error")
            errObj?.optString("message", "HTTP $code") ?: "HTTP $code"
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

    private fun cleanContent(content: String): String {
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
