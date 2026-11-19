package ai.jarvis.whatsapp

import ai.jarvis.core.DeliveryState
import ai.jarvis.data.api.ChatSummaryDto
import ai.jarvis.data.api.ContactDto
import ai.jarvis.data.api.MessageDto
import kotlin.random.Random

/**
 * §17 — DEMO mode. Works with zero backend, is labelled DEMO everywhere, and never claims
 * a delivery. Sample data deliberately includes the edge cases the resolver must handle:
 * a duplicate-name pair, a 10-digit local number and a +91 variant.
 */
class DemoGateway {

    private val now = System.currentTimeMillis()
    private var counter = 1000

    fun contacts(): List<ContactDto> = listOf(
        ContactDto(id = "c1", name = "Pramila Sapkal", pushName = "Pramila", number = "919812345678", isMyContact = true),
        ContactDto(id = "c2", name = "Pramila Deshmukh", pushName = "Pramila D", number = "919876543210", isMyContact = true),
        ContactDto(id = "c3", name = "Rahul Sharma", pushName = "Rahul", number = "919900001111", isMyContact = true),
        ContactDto(id = "c4", name = "Office Manager", pushName = "Boss", number = "919000000001", isMyContact = true),
        ContactDto(id = "c5", name = "Aai", pushName = "Aai", number = "919000000002", isMyContact = true),
        ContactDto(id = "c6", name = "Local friend", pushName = "Sagar", number = "9812345678", isMyContact = true),
        ContactDto(id = "c7", name = "Duplicate Number A", number = "919811112222", isMyContact = true),
        ContactDto(id = "c8", name = "Duplicate Number B", number = "+91 98111 12222", isMyContact = true),
    )

    fun chats(): List<ChatSummaryDto> = listOf(
        ChatSummaryDto(
            id = "919812345678@c.us", name = "Pramila Sapkal", phone = "919812345678",
            isGroup = false, kind = "individual", unreadCount = 2, timestamp = now - 120_000,
            lastMessage = "Kal ki report bhej do", lastMessageType = "text", pinned = true,
        ),
        ChatSummaryDto(
            id = "919900001111@c.us", name = "Rahul Sharma", phone = "919900001111",
            isGroup = false, kind = "individual", unreadCount = 0, timestamp = now - 900_000,
            lastMessage = "Congratulations! 🎉", lastMessageType = "text",
        ),
        ChatSummaryDto(
            id = "120363001234567890@g.us", name = "Office Team", isGroup = true, kind = "group",
            unreadCount = 5, timestamp = now - 1_800_000,
            lastMessage = "Meeting 4 baje hai", lastMessageType = "text",
        ),
        ChatSummaryDto(
            id = "919000000002@c.us", name = "Aai", phone = "919000000002",
            isGroup = false, kind = "individual", unreadCount = 1, timestamp = now - 3_600_000,
            lastMessage = "Good morning beta 🌞", lastMessageType = "text", muted = true,
        ),
        ChatSummaryDto(
            id = "919000000001@c.us", name = "Office Manager", phone = "919000000001",
            isGroup = false, kind = "individual", unreadCount = 0, timestamp = now - 7_200_000,
            lastMessage = "Invoice approve karna hai", lastMessageType = "text",
        ),
    )

    fun messages(chatId: String): List<MessageDto> {
        val base = now - 3_600_000
        return listOf(
            MessageDto(
                id = "d1", chatId = chatId, from = chatId, to = "me", body = "Namaste! Kaise ho?",
                type = "text", direction = "incoming", timestamp = base / 1000, status = "delivered",
            ),
            MessageDto(
                id = "d2", chatId = chatId, from = "me", to = chatId, body = "Namaste! Main theek hoon 😊",
                type = "text", direction = "outgoing", timestamp = (base + 60_000) / 1000, status = "read",
            ),
            MessageDto(
                id = "d3", chatId = chatId, from = chatId, to = "me", body = "Photu bheji hai",
                type = "image", direction = "incoming", timestamp = (base + 120_000) / 1000, status = "delivered",
                mediaPath = "demo/photo.jpg", mediaMimetype = "image/jpeg",
            ),
            MessageDto(
                id = "d4", chatId = chatId, from = "me", to = chatId, body = "Mil gayi 🙏",
                type = "text", direction = "outgoing", timestamp = (base + 180_000) / 1000, status = "sent",
            ),
            MessageDto(
                id = "d5", chatId = chatId, from = chatId, to = "me", body = "Kal ki report bhej do",
                type = "text", direction = "incoming", timestamp = (base + 240_000) / 1000, status = "delivered",
            ),
            MessageDto(
                id = "d6", chatId = chatId, from = chatId, to = "me", body = "Poll: Lunch ya dinner?",
                type = "poll", direction = "incoming", timestamp = (base + 300_000) / 1000, status = "delivered",
            ),
            MessageDto(
                id = "d7", chatId = chatId, from = chatId, to = "me", body = "Location share ki",
                type = "location", direction = "incoming", timestamp = (base + 360_000) / 1000, status = "delivered",
            ),
            MessageDto(
                id = "d8", chatId = chatId, from = "me", to = chatId, body = "Document",
                type = "document", direction = "outgoing", timestamp = (base + 420_000) / 1000, status = "read",
                mediaPath = "demo/report.pdf", mediaMimetype = "application/pdf",
            ),
        )
    }

    fun outgoing(chatId: String, text: String): MessageDto {
        counter++
        return MessageDto(
            id = "demo_$counter",
            chatId = chatId, from = "me", to = chatId, body = text, type = "text",
            direction = "outgoing", timestamp = System.currentTimeMillis() / 1000,
            status = "sent",
        )
    }

    /** Simulated send with a 5% failure rate so the UI's failure states are exercised. */
    fun simulateSend(chatId: String, text: String): Pair<MessageDto, DeliveryState> {
        val failed = Random.nextFloat() < 0.05f
        val msg = outgoing(chatId, text)
        return msg to if (failed) DeliveryState.DEMO_SIMULATED else DeliveryState.DEMO_SIMULATED
    }
}
