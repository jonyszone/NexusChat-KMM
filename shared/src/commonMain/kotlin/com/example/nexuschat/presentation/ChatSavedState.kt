package com.example.nexuschat.presentation

/** Small platform-neutral bridge backed by Android SavedStateHandle. */
interface ChatSavedState {
    fun getString(key: String): String?
    fun setString(key: String, value: String?)
}
