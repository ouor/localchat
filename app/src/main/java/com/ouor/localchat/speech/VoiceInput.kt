package com.ouor.localchat.speech

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** I-08 speech-to-text around the platform SpeechRecognizer (public surface, architecture 10.2). */
@Singleton
class VoiceInput @Inject constructor() {

    val state: StateFlow<VoiceState> get() = TODO("speech")

    /** Starts listening in [languageTag] (app language). Stops by itself after 120 s. Caller holds RECORD_AUDIO. */
    fun start(languageTag: String): Unit = TODO("speech")

    /** Stops and delivers the result. */
    fun stop(): Unit = TODO("speech")

    /** Stops and discards. */
    fun cancel(): Unit = TODO("speech")
}

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val elapsedMs: Long, val rmsDb: Float) : VoiceState
    data class Result(val text: String) : VoiceState
    data class Failed(val reason: VoiceError) : VoiceState
}

enum class VoiceError { NO_SERVICE, NO_SPEECH, FAILED }
