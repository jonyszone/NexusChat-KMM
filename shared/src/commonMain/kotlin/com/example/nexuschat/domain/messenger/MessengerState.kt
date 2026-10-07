package com.example.nexuschat.domain.messenger

/**
 * Delivery is monotonic except that a failed send may be retried from pending.
 * Server and recipient acknowledgements must drive the later states; a local
 * transport write is not equivalent to SENT.
 */
fun DeliveryState.transitionTo(next: DeliveryState): DeliveryState {
    val allowed = when (this) {
        DeliveryState.PENDING -> setOf(DeliveryState.SENDING, DeliveryState.FAILED)
        DeliveryState.SENDING -> setOf(DeliveryState.SENT, DeliveryState.FAILED)
        DeliveryState.SENT -> setOf(DeliveryState.DELIVERED, DeliveryState.FAILED)
        DeliveryState.DELIVERED -> setOf(DeliveryState.READ)
        DeliveryState.READ -> emptySet()
        DeliveryState.FAILED -> setOf(DeliveryState.PENDING)
    }
    require(next == this || next in allowed) { "Invalid delivery transition: $this -> $next" }
    return next
}

fun Message.withDeliveryState(next: DeliveryState, failureCode: String? = null): Message =
    copy(
        deliveryState = deliveryState.transitionTo(next),
        failureCode = if (next == DeliveryState.FAILED) failureCode else null,
    )

fun List<Message>.orderedForConversation(conversationId: String): List<Message> =
    filter { it.conversationId == conversationId }
        .sortedWith(compareBy<Message> { it.sequence == null }.thenBy { it.sequence ?: Long.MAX_VALUE }.thenBy { it.createdAtEpochMillis }.thenBy { it.id })

fun MessageOrderingKey.isAfter(other: MessageOrderingKey): Boolean =
    conversationId == other.conversationId && sequence > other.sequence
