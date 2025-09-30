package com.llamatik.app.feature.chatbot.utils

/**
 * A minimal role/content pair that doesn't assume any vendor template.
 * Add tool/function messages later if you need them.
 */
data class ChatMessage(
    val role: Role,
    val content: String
) {
    enum class Role { System, User, Assistant }
}