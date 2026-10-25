package org.nexora.mesh

enum class DeliveryStatus {
    QUEUED,
    SENT,
    DELIVERED,
    FAILED,
    RECEIVED
}

data class ChatMessage(
    val id: String,
    val chatId: String,
    val body: String,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val status: DeliveryStatus
)

data class Conversation(
    val id: String,
    val title: String,
    val initials: String,
    val isLocalOnly: Boolean,
    val peerTokenHint: String? = null
)

data class ConversationPreview(
    val conversation: Conversation,
    val lastMessage: String,
    val lastTimeLabel: String,
    val unread: Int
)
