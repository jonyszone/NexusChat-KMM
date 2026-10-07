package com.example.nexuschat.presentation

import com.example.nexuschat.data.demo.DemoChatRepository
import com.example.nexuschat.data.model.ChatMode
import com.example.nexuschat.data.model.ChatRole
import com.example.nexuschat.data.model.LlmProvider
import com.example.nexuschat.data.model.TurnState
import com.example.nexuschat.data.network.LlmHttpException
import com.example.nexuschat.data.network.MissingApiKeyException
import com.example.nexuschat.domain.repository.ChatModeStore
import com.example.nexuschat.domain.repository.ChatRepository
import com.example.nexuschat.domain.repository.ChatSession
import com.example.nexuschat.domain.repository.ChatStorage
import com.example.nexuschat.domain.repository.InMemoryChatModeStore
import com.example.nexuschat.domain.repository.InMemoryChatStorage
import com.example.nexuschat.domain.repository.ModeAwareChatRepository
import com.example.nexuschat.testutil.FakeChatRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private var idCounter = 0

    @BeforeTest
    fun setUp() {
        idCounter = 0
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repository: ChatRepository,
        store: ChatModeStore = InMemoryChatModeStore(ChatMode.BYOK)
    ) = ChatViewModel(repository, store, clock = { 1000L }, idGenerator = { "id-${idCounter++}" })

    private suspend fun seedSession(storage: ChatStorage, id: String = "s1") {
        storage.upsertSession(
            ChatSession(id, "Chat", "gpt-4o-mini", mode = ChatMode.BYOK, createdAt = 1, updatedAt = 2)
        )
    }

    @Test
    fun send_persists_the_turn_and_completes_it() = runTest(dispatcher) {
        val storage = InMemoryChatStorage()
        seedSession(storage)
        val repository = FakeChatRepository(storage = storage) { _, _, _ -> flowOf("Hello", " world") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()

        val state = vm.ui.value
        assertFalse(state.isStreaming)
        assertNull(state.pending)
        assertEquals(2, state.messages.size)
        assertEquals(ChatRole.USER, state.messages[0].role)
        assertEquals("hi", state.messages[0].content)
        assertEquals(TurnState.COMPLETED, state.messages[1].state)
        assertEquals("Hello world", state.messages[1].content)
    }

    @Test
    fun missing_key_keeps_user_input_and_marks_the_turn_failed() = runTest(dispatcher) {
        val storage = InMemoryChatStorage()
        seedSession(storage)
        val repository = FakeChatRepository(storage = storage) { _, _, _ ->
            flow { throw MissingApiKeyException(LlmProvider.OPENAI) }
        }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()

        val state = vm.ui.value
        assertFalse(state.isStreaming)
        assertEquals("hi", state.messages.first { it.role == ChatRole.USER }.content)
        val assistant = state.messages.last()
        assertEquals(TurnState.FAILED, assistant.state)
        assertEquals("missing_key", assistant.errorCategory)
        assertTrue(state.lastEvent is ChatEvent.NeedsApiKey)
    }

    @Test
    fun cancel_marks_the_turn_cancelled_and_keeps_partial_text() = runTest(dispatcher) {
        val storage = InMemoryChatStorage()
        seedSession(storage)
        val gate = CompletableDeferred<Unit>()
        val repository = FakeChatRepository(storage = storage) { _, _, _ ->
            flow {
                emit("Hel")
                emit("lo")
                gate.await()
            }
        }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.send("hi")
        runCurrent()
        assertEquals("Hello", vm.ui.value.streamingText)

        vm.cancel()
        advanceUntilIdle()

        val state = vm.ui.value
        assertFalse(state.isStreaming)
        assertNull(state.pending)
        val assistant = state.messages.last()
        assertEquals(TurnState.CANCELLED, assistant.state)
        assertEquals("Hello", assistant.content)
    }

    @Test
    fun failure_after_tokens_is_failed_not_completed() = runTest(dispatcher) {
        val storage = InMemoryChatStorage()
        seedSession(storage)
        val repository = FakeChatRepository(storage = storage) { _, _, _ ->
            flow {
                emit("partial")
                throw LlmHttpException(429, "rate limited")
            }
        }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()

        val assistant = vm.ui.value.messages.last()
        assertEquals(TurnState.FAILED, assistant.state)
        assertEquals("partial", assistant.content)
        assertEquals("http_429", assistant.errorCategory)
        assertTrue(vm.ui.value.lastEvent is ChatEvent.StreamFailed)
    }

    @Test
    fun empty_stream_is_failed_not_completed() = runTest(dispatcher) {
        val storage = InMemoryChatStorage()
        seedSession(storage)
        val repository = FakeChatRepository(storage = storage) { _, _, _ -> emptyFlow() }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()

        val assistant = vm.ui.value.messages.last()
        assertEquals(TurnState.FAILED, assistant.state)
        assertEquals("empty_response", assistant.errorCategory)
        assertFalse(vm.ui.value.isStreaming)
    }

    @Test
    fun retry_reuses_the_turn_without_duplicating_the_answer() = runTest(dispatcher) {
        val storage = InMemoryChatStorage()
        seedSession(storage)
        var calls = 0
        val repository = FakeChatRepository(storage = storage) { _, _, _ ->
            if (calls++ == 0) flow { throw LlmHttpException(500, "boom") } else flowOf("recovered")
        }
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()
        assertTrue(vm.retryAvailable())

        vm.retryLast()
        advanceUntilIdle()

        val messages = vm.ui.value.messages
        assertEquals(2, messages.size) // one user + one (reused) assistant row
        assertEquals(TurnState.COMPLETED, messages[1].state)
        assertEquals("recovered", messages[1].content)
        assertFalse(vm.retryAvailable())
    }

    @Test
    fun demo_and_byok_modes_do_not_share_history() = runTest(dispatcher) {
        val store = InMemoryChatModeStore(ChatMode.DEMO)
        val real = FakeChatRepository(mode = ChatMode.BYOK)
        real.createSession(
            ChatSession("real-1", "Real", "gpt-4o-mini", mode = ChatMode.BYOK, createdAt = 1, updatedAt = 2)
        )
        val router = ModeAwareChatRepository(store, real, DemoChatRepository())
        val vm = viewModel(router, store)
        advanceUntilIdle()

        assertTrue(vm.ui.value.sessions.any { it.id == DemoChatRepository.DEMO_SESSION_ID })
        assertFalse(vm.ui.value.sessions.any { it.id == "real-1" })

        vm.setMode(ChatMode.BYOK)
        advanceUntilIdle()

        val byokSessions = vm.ui.value.sessions
        assertTrue(byokSessions.any { it.id == "real-1" })
        assertFalse(byokSessions.any { it.id == DemoChatRepository.DEMO_SESSION_ID })
    }
}
