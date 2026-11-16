package com.ouor.localchat.speech

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** M-09 / G-04 text-to-speech (public surface, architecture 10.2). */
@Singleton
class ReadAloud @Inject constructor() {

    val state: StateFlow<ReadAloudState> get() = TODO("speech")

    /** Engine voices, app language first. */
    val voices: StateFlow<List<TtsVoice>> get() = TODO("speech")

    /** Reads [markdown] without symbols and code blocks; stops any other message first. */
    fun speak(messageId: String, markdown: String): Unit = TODO("speech")

    fun stop(): Unit = TODO("speech")

    /** G-04 preview with unsaved voice and rate. */
    fun preview(voiceName: String?, rate: Float): Unit = TODO("speech")
}

sealed interface ReadAloudState {
    data object Idle : ReadAloudState
    data class Speaking(val messageId: String) : ReadAloudState

    /** No engine or engine error (toast). */
    data object Unavailable : ReadAloudState
}

data class TtsVoice(val name: String, val localeTag: String)
