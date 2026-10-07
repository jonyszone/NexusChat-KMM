package com.example.nexuschat.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseFramerTest {

    @Test
    fun simple_event_dispatches_on_blank_line() {
        val framer = SseFramer()
        assertNull(framer.feed("data: hello"))
        assertEquals(SseEvent(null, "hello"), framer.feed(""))
    }

    @Test
    fun multiline_data_is_joined_with_newlines() {
        val framer = SseFramer()
        framer.feed("data: line1")
        framer.feed("data: line2")
        assertEquals(SseEvent(null, "line1\nline2"), framer.feed(""))
    }

    @Test
    fun event_name_is_captured() {
        val framer = SseFramer()
        framer.feed("event: content_block_delta")
        framer.feed("data: {}")
        assertEquals(SseEvent("content_block_delta", "{}"), framer.feed(""))
    }

    @Test
    fun comments_and_unknown_fields_are_ignored() {
        val framer = SseFramer()
        assertNull(framer.feed(": keep-alive comment"))
        assertNull(framer.feed("id: 42"))
        assertNull(framer.feed("retry: 1000"))
        framer.feed("data: value")
        assertEquals(SseEvent(null, "value"), framer.feed(""))
    }

    @Test
    fun crlf_line_endings_and_single_space_are_stripped() {
        val framer = SseFramer()
        assertNull(framer.feed("data: crlf\r"))
        assertEquals(SseEvent(null, "crlf"), framer.feed("\r"))
    }

    @Test
    fun finish_flushes_a_trailing_event_without_a_blank_line() {
        val framer = SseFramer()
        framer.feed("event: message_stop")
        framer.feed("data: {}")
        assertEquals(SseEvent("message_stop", "{}"), framer.finish())
    }

    @Test
    fun a_blank_line_with_no_pending_data_dispatches_nothing() {
        val framer = SseFramer()
        assertNull(framer.feed(""))
        assertNull(framer.finish())
    }
}
