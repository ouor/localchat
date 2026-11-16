package com.ouor.localchat.ui.settings

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ouor.localchat.R
import com.ouor.localchat.backup.BackupError
import com.ouor.localchat.backup.BackupException
import com.ouor.localchat.backup.BackupScope
import com.ouor.localchat.backup.BackupService
import com.ouor.localchat.backup.BackupSummary
import com.ouor.localchat.backup.RestoreChoice
import com.ouor.localchat.backup.RestorePlan
import com.ouor.localchat.chat.SessionRepository
import com.ouor.localchat.core.model.Session
import com.ouor.localchat.ui.components.Banner
import com.ouor.localchat.ui.components.BannerVariant
import com.ouor.localchat.ui.components.ButtonVariant
import com.ouor.localchat.ui.components.DialogKind
import com.ouor.localchat.ui.components.ErrorCard
import com.ouor.localchat.ui.components.LcBadge
import com.ouor.localchat.ui.components.LcBottomSheet
import com.ouor.localchat.ui.components.LcButton
import com.ouor.localchat.ui.components.LcCheckbox
import com.ouor.localchat.ui.components.LcDialog
import com.ouor.localchat.ui.components.LcIconButton
import com.ouor.localchat.ui.components.LcLinearProgress
import com.ouor.localchat.ui.components.LcRadio
import com.ouor.localchat.ui.components.LcSnackbarHost
import com.ouor.localchat.ui.components.LcTextField
import com.ouor.localchat.ui.components.LcTopBar
import com.ouor.localchat.ui.components.HelperTone
import com.ouor.localchat.ui.components.SectionLabel
import com.ouor.localchat.ui.components.SettingsChevron
import com.ouor.localchat.ui.components.SettingsGroup
import com.ouor.localchat.ui.components.SettingsRow
import com.ouor.localchat.ui.components.SummaryTiles
import com.ouor.localchat.ui.components.TextFieldKind
import com.ouor.localchat.ui.theme.LcTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** SC-18 sheet steps. */
sealed interface BackupStep {
    /** K-01/K-02: summary and options before the save location is picked. */
    data class Export(val scope: BackupScope, val summary: BackupSummary?) : BackupStep
    data class Working(val done: Long, val total: Long, val restoring: Boolean) : BackupStep
    data object Inspecting : BackupStep
    /** [passwordError]: a wrong password was given for a backup with keys. */
    data class Plan(val plan: RestorePlan, val passwordError: Boolean = false) : BackupStep
    data class Failed(val error: BackupError) : BackupStep
}

sealed interface DataEffect {
    data class Exported(val fileName: String) : DataEffect
    data class Restored(val sessionId: String?) : DataEffect
    data object Deleted : DataEffect
    data object Failed : DataEffect
}

@HiltViewModel
class DataViewModel @Inject constructor(
    private val backup: BackupService,
    private val sessions: SessionRepository,
) : ViewModel() {
    private val _summary = MutableStateFlow<BackupSummary?>(null)
    val summary = _summary.asStateFlow()
    val sessionList: StateFlow<List<Session>> = sessions.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _step = MutableStateFlow<BackupStep?>(null)
    val step = _step.asStateFlow()
    private val _effects = Channel<DataEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    private var job: Job? = null
    /** Restore source, kept to re-inspect with a password (or without, for "키 없이 복원"). */
    private var restoreUri: Uri? = null

    init { refreshSummary() }

    private fun refreshSummary() { viewModelScope.launch { _summary.value = runCatching { backup.summary(BackupScope.Full) }.getOrNull() } }

    fun startExport(scope: BackupScope) {
        _step.value = BackupStep.Export(scope, null)
        viewModelScope.launch { _step.value = BackupStep.Export(scope, runCatching { backup.summary(scope) }.getOrNull()) }
    }

    fun export(scope: BackupScope, uri: Uri, fileName: String, password: CharArray?) {
        job = viewModelScope.launch {
            _step.value = BackupStep.Working(0, 0, restoring = false)
            try {
                backup.export(scope, uri, password).collect { _step.value = BackupStep.Working(it.doneBytes, it.totalBytes, false) }
                _step.value = null
                _effects.send(DataEffect.Exported(fileName))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _step.value = null; _effects.send(DataEffect.Failed)
            } finally { password?.fill('\u0000') }
        }
    }

    fun inspect(uri: Uri, password: CharArray? = null) {
        restoreUri = uri
        job = viewModelScope.launch {
            _step.value = BackupStep.Inspecting
            _step.value = try { BackupStep.Plan(backup.inspect(uri, password)) } catch (e: BackupException) {
                // Wrong password: inspect again without one so the user can still restore without keys (K-03).
                if (e.error == BackupError.WRONG_PASSWORD) runCatching { BackupStep.Plan(backup.inspect(uri, null), passwordError = true) }.getOrElse { BackupStep.Failed(e.error) }
                else BackupStep.Failed(e.error)
            } finally { password?.fill('\u0000') }
        }
    }

    fun retryWithPassword(password: CharArray) { restoreUri?.let { inspect(it, password) } }

    fun restore(plan: RestorePlan, choice: RestoreChoice) {
        job = viewModelScope.launch {
            _step.value = BackupStep.Working(0, 0, restoring = true)
            try {
                backup.restore(plan, choice).collect { _step.value = BackupStep.Working(it.doneBytes, it.totalBytes, true) }
                _step.value = null
                refreshSummary()
                _effects.send(DataEffect.Restored(null))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _step.value = (e as? BackupException)?.let { BackupStep.Failed(it.error) }; if (e !is BackupException) _effects.send(DataEffect.Failed)
            }
        }
    }

    fun cancel() { job?.cancel(); job = null; _step.value = null }
    fun deleteAll() = viewModelScope.launch { sessions.deleteAll(); refreshSummary(); _effects.send(DataEffect.Deleted) }
}

private fun backupFileName(scope: BackupScope) =
    "localchat-" + (if (scope is BackupScope.Session) "chat-" else "") + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".zip"

/** SC-17 + SC-18. [onRestored] goes to the new-chat screen after a restore. */
@Composable
fun DataRoute(vm: DataViewModel, onBack: () -> Unit, onRestored: () -> Unit) {
    val summary by vm.summary.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val sessions by vm.sessionList.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var choosingSession by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is DataEffect.Exported -> snackbar.showSnackbar(context.getString(R.string.ui_backup_saved, e.fileName))
                is DataEffect.Restored -> onRestored()
                DataEffect.Deleted -> snackbar.showSnackbar(context.getString(R.string.ui_all_deleted))
                DataEffect.Failed -> snackbar.showSnackbar(context.getString(R.string.ui_backup_failed))
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::inspect) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LcTopBar(stringResource(R.string.ui_data), navigationIcon = { LcIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back), onBack) })
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
                SectionLabel(stringResource(R.string.ui_backup))
                SettingsGroup {
                    SettingsRow(
                        stringResource(R.string.ui_full_backup),
                        subtitle = summary?.let { stringResource(R.string.ui_full_backup_sub, it.sessions, Formatter.formatShortFileSize(context, it.attachmentBytes)) },
                        onClick = { vm.startExport(BackupScope.Full) },
                    ) { SettingsChevron() }
                    SettingsRow(stringResource(R.string.ui_export_chat), divider = true, onClick = { choosingSession = true }) { SettingsChevron() }
                }
                SectionLabel(stringResource(R.string.ui_restore))
                SettingsGroup {
                    SettingsRow(stringResource(R.string.ui_restore_file), subtitle = stringResource(R.string.ui_restore_file_sub), onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) { SettingsChevron() }
                }
                SectionLabel(stringResource(R.string.ui_danger_zone))
                SettingsGroup { SettingsRow(stringResource(R.string.ui_delete_all_chats), danger = true, onClick = { deleting = true }) }
                Banner(stringResource(R.string.ui_cloud_backup_off), BannerVariant.Info, Modifier.padding(16.dp))
            }
        }
        LcSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
    }

    if (choosingSession) LcBottomSheet({ choosingSession = false }, stringResource(R.string.ui_export_chat)) {
        LazyColumn(Modifier.heightIn(max = 520.dp)) {
            items(sessions, key = { it.id }) { s -> SettingsRow(s.title, onClick = { choosingSession = false; vm.startExport(BackupScope.Session(s.id)) }) }
        }
    }
    if (deleting) LcDialog(
        stringResource(R.string.ui_delete_all_chats), { deleting = false }, stringResource(R.string.ui_delete), { deleting = false; vm.deleteAll() },
        text = stringResource(R.string.ui_delete_all_body), kind = DialogKind.Destructive,
    )
    step?.let { BackupSheet(it, vm, onPickAnother = { open.launch(arrayOf("application/zip", "application/octet-stream")) }) }
}

@Composable
private fun BackupSheet(step: BackupStep, vm: DataViewModel, onPickAnother: () -> Unit) {
    val context = LocalContext.current
    val working = step is BackupStep.Working || step is BackupStep.Inspecting
    LcBottomSheet({ if (!working) vm.cancel() }, title = when (step) {
        is BackupStep.Export -> stringResource(if (step.scope is BackupScope.Full) R.string.ui_full_backup else R.string.ui_export_chat)
        is BackupStep.Working -> stringResource(if (step.restoring) R.string.ui_restoring else R.string.ui_backing_up)
        else -> stringResource(R.string.ui_restore)
    }, locked = working) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (step) {
                is BackupStep.Export -> ExportStep(step, vm)
                is BackupStep.Working -> {
                    val f = if (step.total > 0) step.done.toFloat() / step.total else null
                    LcLinearProgress(f)
                    Text(
                        if (step.total > 0) "${Formatter.formatShortFileSize(context, step.done)} / ${Formatter.formatShortFileSize(context, step.total)} · ${((f ?: 0f) * 100).toInt()}%" else "…",
                        style = LcTheme.text.caption, color = LcTheme.colors.textSecondary,
                    )
                    if (!step.restoring) Text(stringResource(R.string.ui_backup_chat_ok), style = LcTheme.text.caption, color = LcTheme.colors.textTertiary)
                    LcButton(stringResource(R.string.ui_cancel), vm::cancel, variant = ButtonVariant.Outline)
                }
                BackupStep.Inspecting -> { LcLinearProgress(null); Text(stringResource(R.string.ui_checking_file), style = LcTheme.text.body, color = LcTheme.colors.textSecondary) }
                is BackupStep.Plan -> PlanStep(step, vm)
                is BackupStep.Failed -> ErrorCard(
                    stringResource(backupErrorText(step.error)), actionLabel = stringResource(R.string.ui_pick_another_file),
                    onAction = { vm.cancel(); onPickAnother() },
                )
            }
        }
    }
}

@Composable
private fun ExportStep(step: BackupStep.Export, vm: DataViewModel) {
    val context = LocalContext.current
    val full = step.scope is BackupScope.Full
    var withKeys by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val fileName = remember(step.scope) { backupFileName(step.scope) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.export(step.scope, it, fileName, if (withKeys) pw.toCharArray() else null) }
        pw = ""; pw2 = ""
    }
    step.summary?.let { s ->
        SummaryTiles(listOfNotNull(
            stringResource(R.string.ui_chats) to s.sessions.toString(),
            stringResource(R.string.ui_attachments) to "${s.attachments} · ${Formatter.formatShortFileSize(context, s.attachmentBytes)}",
        ))
    } ?: LcLinearProgress(null)
    val valid = !withKeys || (pw.length >= 8 && pw == pw2)
    // Persona-3 review: the password covers only secrets.enc; chats and attachments are a plain ZIP.
    Text(stringResource(R.string.ui_backup_plain_note), style = LcTheme.text.caption, color = LcTheme.colors.textSecondary)
    if (full) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LcCheckbox(withKeys, { withKeys = it })
            Text(stringResource(R.string.ui_include_keys), style = LcTheme.text.body, color = LcTheme.colors.textPrimary)
        }
        if (withKeys) {
            LcTextField(pw, { pw = it }, label = stringResource(R.string.ui_password), kind = TextFieldKind.Password,
                helper = if (pw.isNotEmpty() && pw.length < 8) stringResource(R.string.ui_password_short) else null, helperTone = HelperTone.Error)
            LcTextField(pw2, { pw2 = it }, label = stringResource(R.string.ui_password_again), kind = TextFieldKind.Password,
                helper = if (pw2.isNotEmpty() && pw != pw2) stringResource(R.string.ui_password_mismatch) else null, helperTone = HelperTone.Error)
            Banner(stringResource(R.string.ui_password_warning), BannerVariant.Warn)
        }
    }
    Text(fileName, style = LcTheme.text.caption, color = LcTheme.colors.textTertiary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LcButton(stringResource(R.string.ui_cancel), vm::cancel, Modifier.weight(1f), variant = ButtonVariant.Outline)
        LcButton(stringResource(R.string.ui_choose_location), { save.launch(fileName) }, Modifier.weight(1f), enabled = valid && step.summary != null)
    }
}

@Composable
private fun PlanStep(step: BackupStep.Plan, vm: DataViewModel) {
    val p = step.plan
    LcBadge(stringResource(when (p.kind) { RestorePlan.Kind.FULL -> R.string.ui_kind_full; RestorePlan.Kind.SESSION -> R.string.ui_kind_session; RestorePlan.Kind.DEEPSEEK -> R.string.ui_kind_deepseek }))
    SummaryTiles(listOf(stringResource(R.string.ui_chats) to p.sessions.toString(), stringResource(R.string.ui_attachments) to p.attachments.toString()))
    var choice by remember(p) { mutableStateOf(if (p.conflicts.isNotEmpty()) RestoreChoice.COPY else null) }
    when (p.kind) {
        RestorePlan.Kind.FULL -> {
            Banner(stringResource(R.string.ui_restore_replace_warning), BannerVariant.Err)
            if (p.containsSecrets && !p.secretsUnlocked) {
                var pw by remember { mutableStateOf("") }
                LcTextField(pw, { pw = it }, label = stringResource(R.string.ui_password), kind = TextFieldKind.Password,
                    helper = stringResource(if (step.passwordError) R.string.ui_password_wrong else R.string.ui_password_for_keys), helperTone = if (step.passwordError) HelperTone.Error else HelperTone.Normal)
                LcButton(stringResource(R.string.ui_unlock_keys), { vm.retryWithPassword(pw.toCharArray()); pw = "" }, variant = ButtonVariant.Outline, enabled = pw.isNotEmpty())
            }
        }
        RestorePlan.Kind.SESSION -> if (p.conflicts.isNotEmpty()) {
            Text(stringResource(R.string.ui_restore_conflict), style = LcTheme.text.body, color = LcTheme.colors.textSecondary)
            listOf(RestoreChoice.COPY to R.string.ui_restore_copy, RestoreChoice.OVERWRITE to R.string.ui_restore_overwrite).forEach { (c, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LcRadio(choice == c, { choice = c })
                    Text(stringResource(label), style = LcTheme.text.body, color = LcTheme.colors.textPrimary)
                }
            }
        }
        RestorePlan.Kind.DEEPSEEK -> Text(stringResource(R.string.ui_deepseek_import_note, p.sessions), style = LcTheme.text.body, color = LcTheme.colors.textSecondary)
    }
    val final = when (p.kind) {
        RestorePlan.Kind.FULL -> RestoreChoice.REPLACE_ALL
        RestorePlan.Kind.DEEPSEEK -> RestoreChoice.ADD
        RestorePlan.Kind.SESSION -> choice ?: RestoreChoice.ADD
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LcButton(stringResource(R.string.ui_cancel), vm::cancel, Modifier.weight(1f), variant = ButtonVariant.Outline)
        LcButton(
            stringResource(when { p.kind == RestorePlan.Kind.DEEPSEEK -> R.string.ui_import; p.containsSecrets && !p.secretsUnlocked -> R.string.ui_restore_without_keys; else -> R.string.ui_restore_action }),
            { vm.restore(p, final) }, Modifier.weight(1f),
            variant = if (p.kind == RestorePlan.Kind.FULL) ButtonVariant.Danger else ButtonVariant.Primary,
        )
    }
}

private fun backupErrorText(e: BackupError) = when (e) {
    BackupError.UNRECOGNIZED -> R.string.ui_backup_err_unrecognized
    BackupError.NEWER_VERSION -> R.string.ui_backup_err_newer
    BackupError.BAD_VERSION -> R.string.ui_backup_err_version
    BackupError.INCOMPLETE -> R.string.ui_backup_err_incomplete
    BackupError.BROKEN_TREE -> R.string.ui_backup_err_tree
    BackupError.BROKEN_ATTACHMENT -> R.string.ui_backup_err_attachment
    BackupError.WRONG_PASSWORD -> R.string.ui_password_wrong
}
