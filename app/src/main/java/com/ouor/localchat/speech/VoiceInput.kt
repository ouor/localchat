package com.ouor.localchat.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * I-08 speech-to-text around the platform SpeechRecognizer (public surface, architecture 10.2).
 * Recognizers end a segment after a pause; this restarts them and joins the segments so the user,
 * not the engine, decides when to stop (up to 120 s). Main thread only, like SpeechRecognizer.
 */
@Singleton
class VoiceInput @Inject constructor(@ApplicationContext private val context: Context) {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var language = ""
    private var startedAt = 0L
    private var rms = 0f
    private val segments = mutableListOf<String>()
    private var partial = ""
    /** stop() was called: the next result or error ends the session. */
    private var stopping = false

    private val tick = object : Runnable {
        override fun run() {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (elapsed >= MAX_MS) return stop()
            _state.value = VoiceState.Listening(elapsed, rms)
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Starts listening in [languageTag] (app language). Stops by itself after 120 s. Caller holds RECORD_AUDIO. */
    fun start(languageTag: String) {
        release()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceState.Failed(VoiceError.NO_SERVICE)
            return
        }
        language = languageTag
        segments.clear()
        partial = ""
        rms = 0f
        stopping = false
        startedAt = SystemClock.elapsedRealtime()
        _state.value = VoiceState.Listening(0, 0f)
        listen()
        main.post(tick)
    }

    /** Stops and delivers the result. */
    fun stop() {
        val r = recognizer ?: return
        stopping = true
        main.removeCallbacks(tick)
        r.stopListening()
        // Some engines never answer stopListening; do not leave the UI listening forever.
        main.postDelayed({ if (stopping) finish() }, STOP_GRACE_MS)
    }

    /** Stops and discards. */
    fun cancel() {
        release()
        _state.value = VoiceState.Idle
    }

    private fun listen() {
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    private fun finish(error: VoiceError? = null) {
        if (partial.isNotBlank()) segments += partial
        val text = VoiceRules.join(segments)
        release()
        _state.value = when {
            text.isNotEmpty() -> VoiceState.Result(text)
            else -> VoiceState.Failed(error ?: VoiceError.NO_SPEECH)
        }
    }

    private fun release() {
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        stopping = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onRmsChanged(rmsdB: Float) {
            rms = rmsdB
        }

        override fun onPartialResults(partialResults: Bundle) {
            partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
        }

        override fun onResults(results: Bundle) {
            val best = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            segments += best ?: partial
            partial = ""
            if (stopping) finish() else listen()
        }

        override fun onError(error: Int) {
            when (val outcome = VoiceRules.onError(error, stopping)) {
                VoiceRules.Outcome.Continue -> {
                    if (partial.isNotBlank()) segments += partial
                    partial = ""
                    listen()
                }
                is VoiceRules.Outcome.End -> finish(outcome.error)
            }
        }
    }

    private companion object {
        const val MAX_MS = 120_000L
        const val TICK_MS = 100L
        const val STOP_GRACE_MS = 3_000L
    }
}

/** Pure parts of [VoiceInput]. */
internal object VoiceRules {
    sealed interface Outcome {
        data object Continue : Outcome
        data class End(val error: VoiceError) : Outcome
    }

    /** A pause (no match / silence) mid-session just starts the next segment; real errors end it. */
    fun onError(code: Int, stopping: Boolean): Outcome = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            if (stopping) Outcome.End(VoiceError.NO_SPEECH) else Outcome.Continue
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> Outcome.End(VoiceError.NO_SERVICE)
        else -> Outcome.End(VoiceError.FAILED)
    }

    fun join(segments: List<String>): String = segments.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
}

sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val elapsedMs: Long, val rmsDb: Float) : VoiceState
    data class Result(val text: String) : VoiceState
    data class Failed(val reason: VoiceError) : VoiceState
}

enum class VoiceError { NO_SERVICE, NO_SPEECH, FAILED }
