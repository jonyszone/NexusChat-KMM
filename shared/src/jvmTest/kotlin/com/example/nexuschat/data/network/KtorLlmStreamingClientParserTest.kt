package com.example.nexuschat.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KtorLlmStreamingClientParserTest {
    @Test
    fun openai_delta_extracts_content_and_done_is_ignored() {
        assertEquals(
            "Hello",
            parseOpenAiDelta("""{"choices":[{"delta":{"content":"Hello"}}]}""")
        )
        assertNull(parseOpenAiDelta("[DONE]"))
    }

    @Test
    fun anthropic_parser_accepts_only_content_block_delta() {
        val data = """{"delta":{"text":"world"}}"""

        assertEquals("world", parseAnthropicDelta("content_block_delta", data))
        assertNull(parseAnthropicDelta("message_start", data))
    }

    @Test
    fun gemini_parser_extracts_first_candidate_text() {
        assertEquals(
            "Hi",
            parseGeminiDelta("""{"candidates":[{"content":{"parts":[{"text":"Hi"}]}}]}""")
        )
    }
}
