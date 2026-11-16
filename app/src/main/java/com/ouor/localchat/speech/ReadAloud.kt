package com.ouor.localchat.speech

import android.content.Context
import android.os.LocaleList
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * M-09 / G-04 text-to-speech (public surface, architecture 10.2). The engine binds on first use;
 * calls before it is ready are queued. A failed engine reports Unavailable and is retried on the next call.
 */
@Singleton
class ReadAloud @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow<ReadAloudState>(ReadAloudState.Idle)
    val state: StateFlow<ReadAloudState> = _state.asStateFlow()

    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())

    /** Engine voices, app language first. Filled once the engine is up (first speak/preview/setVoice). */
    val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var voiceName: String? = null
    private var rate = 1.0f

    /** Saved G-04 values used by [speak]; whoever observes AppSettings (ttsVoice, ttsRate) calls this. */
    fun setVoice(voiceName: String?, rate: Float) {
        this.voiceName = voiceName
        this.rate = rate.coerceIn(0.5f, 2.0f)
        withEngine { }
    }

    /** Reads [markdown] without symbols and code blocks; stops any other message first. */
    fun speak(messageId: String, markdown: String) = withEngine { engine ->
        engine.stop()
        apply(engine, voiceName, rate)
        val text = SpeechText.plain(markdown)
        val parts = SpeechText.chunks(text, TextToSpeech.getMaxSpeechInputLength())
        if (parts.isEmpty()) {
            _state.value = ReadAloudState.Idle
            return@withEngine
        }
        _state.value = ReadAloudState.Speaking(messageId)
        parts.forEachIndexed { i, part ->
            val id = if (i == parts.lastIndex) "$messageId#last" else "$messageId#$i"
            engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
        if (_state.value is ReadAloudState.Speaking) _state.value = ReadAloudState.Idle
    }

    /** G-04 preview with unsaved voice and rate. */
    fun preview(voiceName: String?, rate: Float) = withEngine { engine ->
        engine.stop()
        apply(engine, voiceName, rate.coerceIn(0.5f, 2.0f))
        _state.value = ReadAloudState.Speaking(PREVIEW_ID)
        // R by full name: ArchitectureTest reads a root-package import as the app layer.
        engine.speak(context.getString(com.ouor.localchat.R.string.tts_preview_sample), TextToSpeech.QUEUE_FLUSH, null, "$PREVIEW_ID#last")
    }

    private fun withEngine(action: (TextToSpeech) -> Unit) {
        val engine = tts
        if (engine != null && ready) return action(engine)
        pending = { action(tts!!) }
        if (engine != null) return // still initializing
        _state.value = ReadAloudState.Idle
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                tts!!.setOnUtteranceProgressListener(progress)
                _voices.value = listVoices(tts!!)
                pending?.also { pending = null }?.invoke()
            } else {
                fail()
            }
        }
    }

    private fun fail() {
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
        _state.value = ReadAloudState.Unavailable
    }

    private fun apply(engine: TextToSpeech, name: String?, rate: Float) {
        val voice = name?.let { n -> engine.voices?.firstOrNull { it.name == n } }
        (voice ?: engine.defaultVoice)?.let { engine.voice = it }
        engine.setSpeechRate(rate)
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit

        override fun onDone(utteranceId: String) {
            if (utteranceId.endsWith("#last") && _state.value == ReadAloudState.Speaking(utteranceId.removeSuffix("#last"))) {
                _state.value = ReadAloudState.Idle
            }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) = Unit

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) = fail()

        override fun onError(utteranceId: String, errorCode: Int) = fail()
    }

    private fun listVoices(engine: TextToSpeech): List<TtsVoice> {
        val appLanguage = LocaleList.getDefault()[0].language
        return engine.voices.orEmpty()
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .map { TtsVoice(it.name, it.locale.toLanguageTag()) }
            .sortedWith(compareBy<TtsVoice> { !it.localeTag.startsWith(appLanguage) }.thenBy { it.localeTag }.thenBy { it.name })
    }

    private companion object {
        /** Speaking(PREVIEW_ID) while a G-04 preview plays. */
        const val PREVIEW_ID = "tts-preview"
    }
}

sealed interface ReadAloudState {
    data object Idle : ReadAloudState
    data class Speaking(val messageId: String) : ReadAloudState

    /** No engine or engine error (toast). */
    data object Unavailable : ReadAloudState
}

data class TtsVoice(val name: String, val localeTag: String)
