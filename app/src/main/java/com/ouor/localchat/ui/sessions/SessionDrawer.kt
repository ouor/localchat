package com.ouor.localchat.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ouor.localchat.R
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.ui.components.DialogKind
import com.ouor.localchat.ui.components.IconButtonVariant
import com.ouor.localchat.ui.components.LcButton
import com.ouor.localchat.ui.components.ButtonVariant
import com.ouor.localchat.ui.components.LcDialog
import com.ouor.localchat.ui.components.LcIconButton
import com.ouor.localchat.ui.components.LcTextField
import com.ouor.localchat.ui.components.SearchPill
import com.ouor.localchat.ui.components.SectionLabel
import com.ouor.localchat.ui.components.SessionListItem
import com.ouor.localchat.ui.theme.LcTheme

/** SC-05 contents: header + new chat, search, period groups, settings/providers at the bottom. */
@Composable
fun ColumnScope.SessionDrawer(
    state: SessionListUiState,
    selectedId: String?,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onNewChat: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
    onProviders: () -> Unit,
    orderKey: Any? = Unit,
) {
    val c = LcTheme.colors
    // P-STABLE L10: rows keep the order they had when [orderKey] (drawer opened, chat switched) or the query last changed.
    // Snapshot state written only after composition (SideEffect); an unchanged order is equal, so it settles in one pass.
    var shown by remember(orderKey, state.query) { mutableStateOf<List<Pair<SessionGroup, List<String>>>?>(null) }
    val groups = shown?.let { SessionListViewModel.keepOrder(state.groups, it) } ?: state.groups
    androidx.compose.runtime.SideEffect { if (state.loaded) shown = groups.map { (g, rows) -> g to rows.map { it.session.id } } }
    var menuFor by remember { mutableStateOf<Session?>(null) }
    var renaming by remember { mutableStateOf<Session?>(null) }
    var deleting by remember { mutableStateOf<Session?>(null) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.app_name), style = LcTheme.text.title, color = c.textPrimary, modifier = Modifier.weight(1f))
        LcIconButton(Icons.Outlined.EditNote, stringResource(R.string.ui_new_chat), onNewChat, variant = IconButtonVariant.Filled)
    }
    SearchPill(state.query, onQuery, stringResource(R.string.ui_search_chats), Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        if (state.query.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.ui_search_results, state.groups.sumOf { it.second.size })) }
        }
        groups.forEach { (group, rows) ->
            if (state.query.isEmpty()) item(key = "g" + group) { SectionLabel(groupLabel(group)) }
            items(rows, key = { it.session.id }) { row ->
                Box {
                    SessionListItem(
                        row.session.title.ifEmpty { stringResource(R.string.ui_new_chat) },
                        { onOpen(row.session.id) },
                        selected = row.session.id == selectedId,
                        running = row.running,
                        query = state.query,
                        onLongClick = { menuFor = row.session },
                    )
                    DropdownMenu(menuFor == row.session, { menuFor = null }) {
                        DropdownMenuItem({ Text(stringResource(R.string.ui_rename)) }, { menuFor = null; renaming = row.session },
                            leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.ui_delete), color = c.dangerText) }, { menuFor = null; deleting = row.session },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = c.dangerText) })
                    }
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LcButton(stringResource(R.string.ui_settings), onSettings, Modifier.weight(1f), variant = ButtonVariant.Tonal, icon = Icons.Outlined.Settings)
        LcIconButton(Icons.Outlined.Dns, stringResource(R.string.ui_providers), onProviders)
    }

    renaming?.let { s ->
        var t by remember(s.id) { mutableStateOf(s.title) }
        LcDialog(stringResource(R.string.ui_rename), { renaming = null }, stringResource(R.string.ui_save), { onRename(s.id, t.trim()); renaming = null }, confirmEnabled = t.isNotBlank()) {
            LcTextField(t, { t = it })
        }
    }
    deleting?.let { s ->
        LcDialog(
            stringResource(R.string.ui_delete_chat_title), { deleting = null }, stringResource(R.string.ui_delete), { onDelete(s.id); deleting = null },
            text = stringResource(R.string.ui_delete_named_body, s.title), kind = DialogKind.Destructive,
        )
    }
}

@Composable
private fun groupLabel(g: SessionGroup): String = when (g.period) {
    SessionGroup.Period.TODAY -> stringResource(R.string.ui_today)
    SessionGroup.Period.YESTERDAY -> stringResource(R.string.ui_yesterday)
    SessionGroup.Period.WEEK -> stringResource(R.string.ui_last_7_days)
    SessionGroup.Period.MONTH -> stringResource(R.string.ui_last_30_days)
    SessionGroup.Period.OLDER -> g.month.orEmpty()
}
