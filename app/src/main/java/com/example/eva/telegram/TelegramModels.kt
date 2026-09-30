package com.example.eva.telegram

/**
 * Data structures for Telegram Bot API payloads and EVA remote control status.
 */
data class TelegramUser(
    val id: Long,
    val isBot: Boolean,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = null
) {
    val displayName: String
        get() = when {
            !username.isNullOrBlank() -> "@$username"
            !lastName.isNullOrBlank() -> "$firstName $lastName"
            else -> firstName
        }
}

data class TelegramChat(
    val id: Long,
    val type: String, // "private", "group", "supergroup", "channel"
    val title: String? = null,
    val username: String? = null,
    val firstName: String? = null,
    val lastName: String? = null
)

data class TelegramMessage(
    val messageId: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat,
    val date: Long,
    val text: String? = null
)

data class TelegramUpdate(
    val updateId: Long,
    val message: TelegramMessage? = null
)

data class TelegramBotInfo(
    val id: Long,
    val isBot: Boolean,
    val firstName: String,
    val username: String
)

sealed class TelegramBotStatus {
    object Disconnected : TelegramBotStatus()
    object Connecting : TelegramBotStatus()
    data class Running(
        val botUsername: String,
        val isPolling: Boolean = true,
        val lastUpdateTimestamp: Long = System.currentTimeMillis()
    ) : TelegramBotStatus()
    data class Error(val message: String) : TelegramBotStatus()
}

data class TelegramPairingInfo(
    val isPaired: Boolean,
    val pairedChatId: Long? = null,
    val pairedUsername: String? = null
)
