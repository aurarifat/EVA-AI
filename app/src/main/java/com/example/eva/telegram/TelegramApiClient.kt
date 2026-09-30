package com.example.eva.telegram

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Direct Telegram Bot API client communicating with https://api.telegram.org
 * Supports token validation (getMe), long-polling (getUpdates), and response dispatch (sendMessage).
 */
class TelegramApiClient {

    companion object {
        private const val TAG = "TelegramApiClient"
        private const val BASE_URL = "https://api.telegram.org/bot"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // Greater than long-poll timeout
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Validates the BotFather bot token and returns details of the connected bot.
     */
    suspend fun getMe(token: String): Result<TelegramBotInfo> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Telegram token is empty"))
        }

        val url = "$BASE_URL$cleanToken/getMe"
        val request = Request.Builder().url(url).get().build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val desc = runCatching { JSONObject(body).optString("description") }.getOrNull()
                    return@withContext Result.failure(
                        Exception("Telegram API Error (${response.code}): ${desc ?: "Invalid BotFather token"}")
                    )
                }

                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    val desc = json.optString("description", "Failed to retrieve bot info")
                    return@withContext Result.failure(Exception(desc))
                }

                val resultObj = json.getJSONObject("result")
                val botInfo = TelegramBotInfo(
                    id = resultObj.getLong("id"),
                    isBot = resultObj.optBoolean("is_bot", true),
                    firstName = resultObj.optString("first_name", "EVA Bot"),
                    username = resultObj.optString("username", "")
                )
                Result.success(botInfo)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMe error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Long-polls Telegram for new messages.
     * @param offset Identifier of the first update to be returned (last update_id + 1)
     * @param timeoutSeconds Long polling timeout in seconds
     */
    suspend fun getUpdates(
        token: String,
        offset: Long? = null,
        timeoutSeconds: Int = 20
    ): Result<List<TelegramUpdate>> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val url = "$BASE_URL$cleanToken/getUpdates"

        val jsonBody = JSONObject().apply {
            if (offset != null) put("offset", offset)
            put("timeout", timeoutSeconds)
            put("allowed_updates", JSONArray().apply { put("message") })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val desc = runCatching { JSONObject(body).optString("description") }.getOrNull()
                    return@withContext Result.failure(
                        Exception("getUpdates failed (${response.code}): ${desc ?: response.message}")
                    )
                }

                val json = JSONObject(body)
                if (!json.optBoolean("ok", false)) {
                    return@withContext Result.failure(
                        Exception(json.optString("description", "Unknown error in getUpdates"))
                    )
                }

                val resultsArray = json.optJSONArray("result") ?: JSONArray()
                val updates = mutableListOf<TelegramUpdate>()

                for (i in 0 until resultsArray.length()) {
                    val updateObj = resultsArray.getJSONObject(i)
                    val updateId = updateObj.getLong("update_id")
                    val msgObj = updateObj.optJSONObject("message")

                    val message = if (msgObj != null) {
                        val msgId = msgObj.getLong("message_id")
                        val date = msgObj.optLong("date", System.currentTimeMillis() / 1000)
                        val text = msgObj.optString("text", null)

                        val fromObj = msgObj.optJSONObject("from")
                        val from = if (fromObj != null) {
                            TelegramUser(
                                id = fromObj.getLong("id"),
                                isBot = fromObj.optBoolean("is_bot", false),
                                firstName = fromObj.optString("first_name", ""),
                                lastName = fromObj.optString("last_name", null),
                                username = fromObj.optString("username", null)
                            )
                        } else null

                        val chatObj = msgObj.getJSONObject("chat")
                        val chat = TelegramChat(
                            id = chatObj.getLong("id"),
                            type = chatObj.optString("type", "private"),
                            title = chatObj.optString("title", null),
                            username = chatObj.optString("username", null),
                            firstName = chatObj.optString("first_name", null),
                            lastName = chatObj.optString("last_name", null)
                        )

                        TelegramMessage(
                            messageId = msgId,
                            from = from,
                            chat = chat,
                            date = date,
                            text = text
                        )
                    } else null

                    updates.add(TelegramUpdate(updateId = updateId, message = message))
                }

                Result.success(updates)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getUpdates error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Sends a message to a given chat ID.
     * Chunks text automatically if length exceeds Telegram's 4096 character limit.
     * Falls back to plain text if Markdown entity formatting fails.
     */
    suspend fun sendMessage(
        token: String,
        chatId: Long,
        text: String,
        parseMode: String? = "Markdown",
        replyToMessageId: Long? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val url = "$BASE_URL$cleanToken/sendMessage"

        // Telegram max message size is 4096. Split into chunks if necessary.
        val chunks = text.chunked(3800)
        var allSuccess = true

        for (chunk in chunks) {
            val success = sendSingleMessage(url, chatId, chunk, parseMode, replyToMessageId)
            if (!success) {
                allSuccess = false
            }
        }

        if (allSuccess) Result.success(true) else Result.failure(Exception("Failed to send message chunk"))
    }

    private fun sendSingleMessage(
        url: String,
        chatId: Long,
        textChunk: String,
        parseMode: String?,
        replyToMessageId: Long?
    ): Boolean {
        fun buildBody(mode: String?): String {
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", textChunk)
                if (mode != null) put("parse_mode", mode)
                if (replyToMessageId != null) put("reply_to_message_id", replyToMessageId)
            }
            return json.toString()
        }

        // Try sending with requested parse mode
        try {
            val request = Request.Builder()
                .url(url)
                .post(buildBody(parseMode).toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) return true

                val respBody = response.body?.string().orEmpty()
                Log.w(TAG, "sendMessage with parseMode=$parseMode returned ${response.code}: $respBody")

                // If error is Markdown parse entity issue, retry without parseMode
                if (parseMode != null && response.code == 400) {
                    val fallbackRequest = Request.Builder()
                        .url(url)
                        .post(buildBody(null).toRequestBody(JSON_MEDIA_TYPE))
                        .build()

                    httpClient.newCall(fallbackRequest).execute().use { fallbackResp ->
                        return fallbackResp.isSuccessful
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending message to chat $chatId: ${e.message}", e)
        }
        return false
    }

    /**
     * Sends typing chat action to notify Telegram user that EVA is computing the answer.
     */
    suspend fun sendChatAction(
        token: String,
        chatId: Long,
        action: String = "typing"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val url = "$BASE_URL$cleanToken/sendChatAction"

        val json = JSONObject().apply {
            put("chat_id", chatId)
            put("action", action)
        }

        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
