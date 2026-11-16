package com.ouor.localchat.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ouor.localchat.chat.ChatEngine
import com.ouor.localchat.chat.SessionRepository
import com.ouor.localchat.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** C-05 period buckets; older sessions group by month ([month] = "yyyy-MM"). */
data class SessionGroup(val period: Period, val month: String? = null) {
    enum class Period { TODAY, YESTERDAY, WEEK, MONTH, OLDER }
}

data class SessionRowUi(val session: Session, val running: Boolean)

data class SessionListUiState(
    val query: String = "",
    val groups: List<Pair<SessionGroup, List<SessionRowUi>>> = emptyList(),
    val loaded: Boolean = false,
)

/** SC-05 (drawer / wide-screen panel). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionListViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val engine: ChatEngine,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val state: StateFlow<SessionListUiState> = combine(query, query.flatMapLatest { sessions.observeSessions(it) }, engine.runs) { q, list, runs ->
        SessionListUiState(q, group(list.map { SessionRowUi(it, runs.containsKey(it.id)) }, LocalDate.now()), loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionListUiState())

    fun onQuery(q: String) { query.value = q }
    fun rename(id: String, title: String) = viewModelScope.launch { sessions.rename(id, title) }
    fun delete(id: String) = viewModelScope.launch { engine.stop(id); sessions.delete(id) }

    companion object {
        fun group(rows: List<SessionRowUi>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Pair<SessionGroup, List<SessionRowUi>>> =
            rows.groupBy { r ->
                val d = Instant.ofEpochMilli(r.session.updatedAt).atZone(zone).toLocalDate()
                when {
                    !d.isBefore(today) -> SessionGroup(SessionGroup.Period.TODAY)
                    d == today.minusDays(1) -> SessionGroup(SessionGroup.Period.YESTERDAY)
                    d.isAfter(today.minusDays(7)) -> SessionGroup(SessionGroup.Period.WEEK)
                    d.isAfter(today.minusDays(30)) -> SessionGroup(SessionGroup.Period.MONTH)
                    else -> SessionGroup(SessionGroup.Period.OLDER, "%d-%02d".format(d.year, d.monthValue))
                }
            }.toList() // input is newest first, so groups come out in order

        /**
         * P-STABLE L10: [current] rows laid out in the order [shown] (group → ids) had, so a reply finishing in another chat
         * does not reorder the list under the finger. Rows not shown before (a new chat) go first in their group; gone rows drop.
         */
        fun keepOrder(current: List<Pair<SessionGroup, List<SessionRowUi>>>, shown: List<Pair<SessionGroup, List<String>>>): List<Pair<SessionGroup, List<SessionRowUi>>> {
            val byId = current.flatMap { it.second }.associateBy { it.session.id }
            val known = shown.flatMap { it.second }.toSet()
            val out = shown.map { (g, ids) -> g to ids.mapNotNull(byId::get) }.toMutableList()
            current.forEach { (g, rows) ->
                val fresh = rows.filter { it.session.id !in known }
                if (fresh.isEmpty()) return@forEach
                val i = out.indexOfFirst { it.first == g }
                if (i >= 0) out[i] = g to fresh + out[i].second else out.add(0, g to fresh)
            }
            return out.filter { it.second.isNotEmpty() }
        }
    }
}
