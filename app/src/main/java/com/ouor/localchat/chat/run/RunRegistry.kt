package com.ouor.localchat.chat.run

import com.ouor.localchat.core.ApplicationScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One run per session, in the application scope (architecture 5.1). */
@Singleton
class RunRegistry @Inject constructor(@ApplicationScope private val scope: CoroutineScope) {

    private val _runs = MutableStateFlow<Map<String, RunState>>(emptyMap())
    val runs: StateFlow<Map<String, RunState>> = _runs.asStateFlow()

    private val jobs = HashMap<String, Job>()
    private val pauses = HashMap<String, Pair<Pause, CompletableDeferred<Resolution>>>()

    /** Returns false when [initial].sessionId already has a run. */
    fun start(initial: RunState, block: suspend (RunHandle) -> Unit): Boolean = synchronized(this) {
        val sessionId = initial.sessionId
        if (sessionId in jobs) return false
        _runs.update { it + (sessionId to initial) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block(Handle(sessionId))
            } finally {
                synchronized(this@RunRegistry) {
                    jobs.remove(sessionId)
                    pauses.remove(sessionId)
                    _runs.update { it - sessionId }
                }
            }
        }
        jobs[sessionId] = job
        job.start()
        true
    }

    fun stop(sessionId: String) {
        synchronized(this) { jobs[sessionId] }?.cancel()
    }

    /** Cancels every run and waits until each has saved. */
    suspend fun stopAll() {
        val all = synchronized(this) { jobs.values.toList() }
        all.forEach { it.cancel() }
        all.joinAll()
    }

    /** Completes the current pause when [resolution] answers it; anything else is ignored. */
    fun resolve(sessionId: String, resolution: Resolution) {
        val (pause, deferred) = synchronized(this) { pauses[sessionId] } ?: return
        val matches = when (pause) {
            is Pause.Approval -> resolution is Resolution.ApprovalAnswer && resolution.id == pause.id
            is Pause.StepLimit -> resolution is Resolution.StepLimitAnswer
            is Pause.Handoff -> resolution is Resolution.HandoffReturned
            is Pause.UserControl -> false
        }
        if (matches) deferred.complete(resolution)
    }

    /** The loop's view of its own run. */
    interface RunHandle {
        val state: RunState
        fun update(transform: (RunState) -> RunState)

        /** Shows [pause] and suspends until resolved; the previous phase is restored afterwards. */
        suspend fun pause(pause: Pause): Resolution
    }

    private inner class Handle(private val sessionId: String) : RunHandle {
        override val state: RunState get() = _runs.value.getValue(sessionId)

        override fun update(transform: (RunState) -> RunState) {
            _runs.update { map -> map[sessionId]?.let { map + (sessionId to transform(it)) } ?: map }
        }

        override suspend fun pause(pause: Pause): Resolution {
            val deferred = CompletableDeferred<Resolution>()
            val before = state.phase
            synchronized(this@RunRegistry) { pauses[sessionId] = pause to deferred }
            update { it.copy(phase = RunPhase.Paused(pause)) }
            try {
                return deferred.await()
            } finally {
                synchronized(this@RunRegistry) { pauses.remove(sessionId) }
                update { it.copy(phase = before) }
            }
        }
    }
}
