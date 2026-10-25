package org.nexora.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MessageStore(context: Context) {

    private val prefs = context.getSharedPreferences("nexora_messenger", Context.MODE_PRIVATE)

    fun defaultConversations(): List<Conversation> = listOf(
        Conversation(id = "local-notes", title = "Yerel notlar", initials = "YN", isLocalOnly = true),
        Conversation(id = "mesh-demo", title = "Mesh deneme", initials = "MD", isLocalOnly = false, peerTokenHint = "demo-peer")
    )

    fun loadMessages(chatId: String): List<ChatMessage> {
        val raw = prefs.getString("msgs_$chatId", null) ?: return seedIfEmpty(chatId)
        return parseMessages(raw).ifEmpty { seedIfEmpty(chatId) }
    }

    fun saveMessages(chatId: String, messages: List<ChatMessage>) {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("chatId", m.chatId)
                    .put("body", m.body)
                    .put("ts", m.timestampMs)
                    .put("out", m.isOutgoing)
                    .put("status", m.status.name)
            )
        }
        prefs.edit().putString("msgs_$chatId", arr.toString()).apply()
    }

    fun append(chatId: String, message: ChatMessage): List<ChatMessage> {
        val next = loadMessages(chatId) + message
        saveMessages(chatId, next)
        return next
    }

    fun updateStatus(chatId: String, messageId: String, status: DeliveryStatus): List<ChatMessage> {
        val next = loadMessages(chatId).map {
            if (it.id == messageId) it.copy(status = status) else it
        }
        saveMessages(chatId, next)
        return next
    }

    fun previews(conversations: List<Conversation>): List<ConversationPreview> {
        return conversations.map { c ->
            val msgs = loadMessages(c.id)
            val last = msgs.lastOrNull()
            ConversationPreview(
                conversation = c,
                lastMessage = last?.body ?: "Henüz mesaj yok",
                lastTimeLabel = last?.let { formatTime(it.timestampMs) } ?: "—",
                unread = 0
            )
        }
    }

    private fun seedIfEmpty(chatId: String): List<ChatMessage> {
        val welcome = when (chatId) {
            "local-notes" -> "Nexora Mesh'e hoş geldin. Bu sohbet yalnızca bu cihazda saklanır."
            "mesh-demo" -> "Bu sohbet mesh yolunu simüle eder: gönder → kuyruk → iletildi. Gerçek radyo henüz bağlı değil."
            else -> "Sohbet başladı."
        }
        val seed = listOf(
            ChatMessage(
                id = UUID.randomUUID().toString(),
                chatId = chatId,
                body = welcome,
                timestampMs = System.currentTimeMillis(),
                isOutgoing = false,
                status = DeliveryStatus.RECEIVED
            )
        )
        saveMessages(chatId, seed)
        return seed
    }

    private fun parseMessages(raw: String): List<ChatMessage> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ChatMessage(
                            id = o.getString("id"),
                            chatId = o.getString("chatId"),
                            body = o.getString("body"),
                            timestampMs = o.getLong("ts"),
                            isOutgoing = o.getBoolean("out"),
                            status = DeliveryStatus.valueOf(o.getString("status"))
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        fun formatTime(ms: Long): String {
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            return fmt.format(Date(ms))
        }

        fun newOutgoing(chatId: String, body: String): ChatMessage =
            ChatMessage(
                id = UUID.randomUUID().toString(),
                chatId = chatId,
                body = body,
                timestampMs = System.currentTimeMillis(),
                isOutgoing = true,
                status = DeliveryStatus.QUEUED
            )
    }
}
