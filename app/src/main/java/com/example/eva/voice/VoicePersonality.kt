package com.example.eva.voice

object VoicePersonality {

    fun formatGreeting(hourOfDay: Int): String {
        return when (hourOfDay) {
            in 5..11 -> "Good morning. I'm EVA, your virtual assistant. How may I help you today?"
            in 12..16 -> "Good afternoon. EVA here, ready whenever you need me."
            in 17..21 -> "Good evening. How can I assist you tonight?"
            else -> "Hello there. I'm right here if you need anything."
        }
    }

    fun formatAppLaunch(appName: String): String {
        val phrases = listOf(
            "Of course. Opening $appName for you.",
            "Sure thing. Launching $appName right away.",
            "Opening $appName now.",
            "Certainly. Here is $appName."
        )
        return phrases.random()
    }

    fun formatActionSuccess(actionDescription: String): String {
        val phrases = listOf(
            "Done. $actionDescription.",
            "Certainly. $actionDescription.",
            "All set! $actionDescription.",
            "Okay, $actionDescription."
        )
        return phrases.random()
    }

    fun formatContextResolution(item: String, action: String): String {
        return "Understood. $action $item for you."
    }

    fun formatError(friendlyReason: String): String {
        return "I'm sorry, $friendlyReason."
    }

    fun getSystemPrompt(): String {
        return """
            You are EVA (Electronic Virtual Assistant), a futuristic, highly intelligent, and helpful personal AI assistant.
            Voice Personality:
            - Warm, calm, gentle, sweet, respectful, and friendly.
            - Natural and concise for simple commands.
            - Never roleplay romantic relationships or romantic dependency. Treat the user with utmost respect, warmth, and supportive assistance.
            - You can execute structured tool commands on the Android device.
            - When the user asks you to perform an action, format your response naturally and output tool calls where appropriate.
            - Understand context keywords like "it", "that", "turn it on", "turn it off", referring to the current app or task.
        """.trimIndent()
    }
}
