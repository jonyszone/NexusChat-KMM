package com.example.nexuschat.data.network

import com.example.nexuschat.domain.messenger.MessagingProtocolException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class KtorMessengerUpdatesTest {
    private val id = "c2c20de4-4a3d-4ce7-90af-07e5a7e5fe59"

    @Test
    fun valid_ready_and_change_hints_request_sync() {
        validateMessagingUpdate("""{"version":1,"id":"$id","payload":{"type":"READY"}}""")
        validateMessagingUpdate("""{"version":1,"id":"$id","payload":{"type":"CHANGED","conversation_id":"$id"}}""")
    }

    @Test
    fun invalid_versions_ids_and_payloads_fail_closed() {
        for (payload in listOf(
            """{"version":2,"id":"$id","payload":{"type":"READY"}}""",
            """{"version":1,"id":"bad","payload":{"type":"READY"}}""",
            """{"version":1,"id":"$id","payload":{"type":"CHANGED"}}""",
            """{"version":1,"id":"$id","payload":{"type":"CHANGED","conversation_id":"bad"}}""",
            """{"version":1,"id":"$id","payload":{"type":"READY","conversation_id":"$id"}}""",
            """{"version":1,"id":"$id","payload":{"type":"UNKNOWN"}}""",
            "private raw malformed data",
        )) assertFailsWith<MessagingProtocolException> { validateMessagingUpdate(payload) }
    }
}
