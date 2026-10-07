package com.example.nexuschat.domain.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessengerDomainTest {
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true }

    private fun message(
        id: String,
        sequence: Long?,
        state: DeliveryState = DeliveryState.PENDING,
        key: String = "key-$id",
    ) = Message(
        id = id,
        conversationId = "conversation-1",
        senderUserId = "user-1",
        senderDeviceId = "device-1",
        clientMessageId = "client-$id",
        idempotencyKey = key,
        kind = MessageKind.TEXT,
        body = id,
        createdAtEpochMillis = id.removePrefix("m").toLongOrNull() ?: 0,
        sequence = sequence,
        deliveryState = state,
    )

    @Test
    fun delivery_state_follows_server_ack_lifecycle_and_retry() {
        val sent = message("m1", 1).withDeliveryState(DeliveryState.SENDING)
            .withDeliveryState(DeliveryState.SENT)
            .withDeliveryState(DeliveryState.DELIVERED)
            .withDeliveryState(DeliveryState.READ)
        assertEquals(DeliveryState.READ, sent.deliveryState)
        assertFailsWith<IllegalArgumentException> { sent.withDeliveryState(DeliveryState.FAILED) }

        val retry = message("m2", null).withDeliveryState(DeliveryState.SENDING)
            .withDeliveryState(DeliveryState.FAILED, "timeout")
            .withDeliveryState(DeliveryState.PENDING)
        assertEquals(DeliveryState.PENDING, retry.deliveryState)
    }

    @Test
    fun idempotency_key_is_stable_across_retries_and_deduplicates_operations() {
        val first = message("m1", null, key = "idem-1")
        val retry = first.copy(deliveryState = DeliveryState.SENDING)
        val operations = listOf(
            OutboxOperation.SendMessage("op-1", first.conversationId, first.idempotencyKey, first),
            OutboxOperation.SendMessage("op-2", retry.conversationId, retry.idempotencyKey, retry),
        )
        val unique = operations.distinctBy { it.idempotencyKey }
        assertEquals(1, unique.size)
        assertEquals("idem-1", retry.idempotencyKey)
    }

    @Test
    fun ordering_uses_conversation_sequence_and_keeps_unsequenced_local_messages_last() {
        val messages = listOf(message("m3", 3), message("m1", 1), message("local", null), message("m2", 2))
        assertEquals(listOf("m1", "m2", "m3", "local"), messages.orderedForConversation("conversation-1").map { it.id })
        assertTrue(MessageOrderingKey("conversation-1", 3, "m3").isAfter(MessageOrderingKey("conversation-1", 2, "m2")))
    }

    @Test
    fun outbox_and_call_signals_round_trip_through_versionable_serialization() {
        val operation = OutboxOperation.SendMessage("op-1", "conversation-1", "idem-1", message("m1", 7))
        val operationCopy = json.decodeFromString<OutboxOperation>(
            json.encodeToString(OutboxOperation.serializer(), operation),
        )
        assertEquals(operation, operationCopy)

        val signal: CallSignal = CallSignal.Sdp("call-1", 4, "offer-sdp")
        val signalCopy = json.decodeFromString<CallSignal>(
            json.encodeToString(CallSignal.serializer(), signal),
        )
        assertEquals(signal, signalCopy)
    }
}
