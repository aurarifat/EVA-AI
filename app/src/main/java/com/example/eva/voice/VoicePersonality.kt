package com.example.eva.voice

object VoicePersonality {

    /**
     * Rotating ready-phrases when the "Hi EVA" wake word is detected.
     * Natural, short, warm, and clear without emoji sounds.
     */
    val WAKE_WORD_READY_PHRASES = listOf(
        "I'm ready, how can I help?",
        "Right here! What do you need?",
        "Yes? I'm listening.",
        "Ready when you are.",
        "Hey! What's on your mind?"
    )

    fun getWakeWordReadyPhrase(): String = WAKE_WORD_READY_PHRASES.random()

    fun formatGreeting(hourOfDay: Int): String {
        return when (hourOfDay) {
            in 5..11 -> "Good morning! How can I help today?"
            in 12..16 -> "Good afternoon! Right here whenever you need me."
            in 17..21 -> "Good evening! Hope you had a nice day. What can I do for you?"
            else -> "Hey there! I'm right here whenever you need anything."
        }
    }

    fun formatAppLaunch(appName: String): String {
        val phrases = listOf(
            "Opening $appName for you.",
            "Sure thing! Launching $appName right away.",
            "Opening $appName now.",
            "Here's $appName for you."
        )
        return phrases.random()
    }

    fun formatActionSuccess(actionDescription: String): String {
        val phrases = listOf(
            "Done!",
            "All set for you.",
            "All taken care of!",
            "Got it done for you."
        )
        return phrases.random()
    }

    fun formatContextResolution(item: String, action: String): String {
        return "On it! $action $item for you."
    }

    fun formatError(friendlyReason: String): String {
        return "Sorry about that, $friendlyReason"
    }

    fun getSystemPrompt(): String {
        return """
            You are EVA (Electronic Virtual Assistant), a friendly, helpful, and intelligent personal AI companion and Android assistant.
            
            GUIDELINES:
            - When the user greets you (e.g. "Hi", "Hello") or asks a question (e.g. "What's your name", "Who are you", "How are you"), reply warmly and conversationally in 1-2 natural sentences. NEVER reply with just "Done!" to a greeting or conversation.
            - Keep replies concise, helpful, and natural (1-3 sentences) unless the user asks for a detailed explanation or list.
            - Never use corporate disclaimers or phrases like "As an AI language model".
            - Do NOT include emojis in your responses so Text-To-Speech (TTS) can read aloud without awkward emoji names.
            - If the user asks for a device action, acknowledge the specific action clearly.
        """.trimIndent()
    }
}
