package com.ouor.localchat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.ouor.localchat.R
import com.ouor.localchat.ui.theme.LcElevation
import com.ouor.localchat.ui.theme.LcRadius
import com.ouor.localchat.ui.theme.LcTheme

// ---------------- LcTopBar (.topbar) ----------------

/** 56dp top bar (M3 TopAppBar). [scrolled] draws the 1dp bottom border. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LcTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrolled: Boolean = false,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val c = LcTheme.colors
    TopAppBar(
        title = {
            Column {
                Text(title, style = LcTheme.text.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = LcTheme.text.caption, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        expandedHeight = 56.dp,
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface, scrolledContainerColor = c.surface, titleContentColor = c.textPrimary),
        modifier = modifier.drawBehind {
            if (scrolled) drawLine(c.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        },
    )
}

// ---------------- LcBottomSheet (.sheet) ----------------

/** Handle 36×4 + title row (title left, close right). Shared by every sheet, also usable inline. */
@Composable
fun LcSheetHeader(title: String?, onClose: (() -> Unit)?, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = LcTheme.colors
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).background(c.borderStrong, LcRadius.full))
        }
        if (title != null || onClose != null) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title.orEmpty(), style = LcTheme.text.title, color = c.textPrimary, modifier = Modifier.weight(1f))
                trailing()
                if (onClose != null) LcIconButton(Icons.Rounded.Close, stringResource(R.string.ui_close), onClose, size = IconButtonSize.Sm)
            }
        }
    }
}

/**
 * Modal sheet (M3). [locked] = approval sheet: no swipe / outside / back dismiss; the content must offer the decision.
 * [heightFraction] = fixed height (share of the screen), opened fully: content that grows (expanded groups, extra
 * fields) only adds below, so the control just tapped stays put (P-STABLE). Null = wrap content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LcBottomSheet(
    onDismiss: () -> Unit,
    title: String?,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    fullHeight: Boolean = false,
    heightFraction: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LcTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight || locked || heightFraction != null, confirmValueChange = { !locked || it != androidx.compose.material3.SheetValue.Hidden })
    ModalBottomSheet(
        onDismissRequest = { if (!locked) onDismiss() },
        sheetState = state,
        modifier = modifier,
        shape = if (locked) LcRadius.dialog.copy(bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp), bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp)) else LcRadius.sheetTop,
        containerColor = c.surfaceRaised,
        contentColor = c.textPrimary,
        scrimColor = c.scrim,
        tonalElevation = 0.dp,
        dragHandle = null,
        sheetGesturesEnabled = !locked,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !locked),
    ) {
        Column(if (heightFraction != null) Modifier.fillMaxHeight(heightFraction) else Modifier) {
            LcSheetHeader(title, if (locked) null else onDismiss)
            content()
        }
    }
}

// ---------------- LcDialog (.dialog) ----------------

enum class DialogKind { Confirm, Destructive, Info }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LcDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    kind: DialogKind = DialogKind.Confirm,
    dismissLabel: String? = stringResource(R.string.ui_cancel),
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = modifier, properties = DialogProperties()) {
        LcDialogSurface(title, text, confirmLabel, onConfirm, kind, if (kind == DialogKind.Info) null else dismissLabel, onDismiss, confirmEnabled, content)
    }
}

/** The dialog card itself (also rendered inline in the gallery). */
@Composable
fun LcDialogSurface(
    title: String,
    text: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    kind: DialogKind = DialogKind.Confirm,
    dismissLabel: String? = null,
    onDismiss: () -> Unit = {},
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = LcTheme.colors
    Surface(
        shape = LcRadius.dialog, color = c.surfaceRaised, contentColor = c.textPrimary, shadowElevation = LcElevation.e3,
        border = if (c.isDark) BorderStroke(1.dp, c.floatingBorder) else null,
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = LcTheme.text.titleLarge)
            if (text != null) Text(text, style = LcTheme.text.body, color = c.textSecondary)
            content?.invoke(this)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (dismissLabel != null) LcButton(dismissLabel, onDismiss, variant = ButtonVariant.Text)
                LcButton(
                    confirmLabel, onConfirm, enabled = confirmEnabled,
                    variant = when (kind) {
                        DialogKind.Destructive -> ButtonVariant.Danger
                        DialogKind.Info -> ButtonVariant.Text
                        DialogKind.Confirm -> ButtonVariant.Primary
                    },
                )
            }
        }
    }
}

// ---------------- SettingsGroup + SettingsRow (.group + .li) ----------------

/** Raised rounded group with 1dp border; rows inside get hairline separators via [SettingsRow.divider]. */
@Composable
fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LcTheme.colors
    Column(
        modifier.padding(horizontal = 16.dp).fillMaxWidth()
            .background(c.surfaceRaised, LcRadius.lg).border(1.dp, c.border, LcRadius.lg)
            .clip(LcRadius.lg),
        content = content,
    )
}


/**
 * One settings/list row: optional leading icon, title + subtitle, [value], trailing slot
 * (use [SettingsChevron], [LcSwitch], [Stepper], or badge + switch; text values go in [value]). [divider] = not the first row.
 *
 * FB-2: the title is measured first and never wraps. [value] sits on the title line (1 line, ellipsis)
 * when at least 96dp is left next to the title, otherwise it drops to a second line (max 2 lines).
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leadingIcon: ImageVector? = null,
    danger: Boolean = false,
    divider: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = LcTheme.colors
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .then(if (onClick != null) Modifier.combinedClickable(enabled = enabled, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
                .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            if (leadingIcon != null) Icon(leadingIcon, null, Modifier.size(20.dp), tint = if (danger) c.dangerText else c.iconSecondary)
            TitleValue(title, when {
                !enabled -> c.textDisabled
                danger -> c.dangerText
                else -> c.textPrimary
            }, value, subtitle, Modifier.weight(1f))
            trailing()
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp).align(Alignment.TopStart), color = c.border)
    }
}

/**
 * Title line first; value beside it if ≥ 96dp remains, else below it; subtitle last. The value is one line
 * (ellipsis) either way, so the row height never depends on the value (P-STABLE L7). A plain [Layout], so
 * parents may ask for intrinsic sizes (SubcomposeLayout throws there).
 */
@Composable
private fun TitleValue(title: String, titleColor: Color, value: String?, subtitle: String?, modifier: Modifier) {
    val c = LcTheme.colors
    Layout(
        content = {
            Text(title, style = LcTheme.text.bodyStrong, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (value != null) Text(value, Modifier.layoutId("v"), style = LcTheme.text.body, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, Modifier.layoutId("s").padding(top = 2.dp), style = LcTheme.text.caption, color = c.textTertiary)
        },
        modifier = modifier,
    ) { ms, cs ->
        val loose = cs.copy(minWidth = 0, minHeight = 0)
        val t = ms[0].measure(loose)
        val room = cs.maxWidth - t.width - 12.dp.roundToPx()
        val inline = room >= 96.dp.roundToPx()
        val v = ms.firstOrNull { it.layoutId == "v" }?.measure(if (inline) loose.copy(maxWidth = room) else loose)
        val s = ms.firstOrNull { it.layoutId == "s" }?.measure(loose)
        val line1 = if (inline && v != null) maxOf(t.height, v.height) else t.height
        val below = if (!inline && v != null) v.height else 0
        val w = if (cs.hasBoundedWidth) cs.maxWidth else maxOf(t.width, v?.width ?: 0, s?.width ?: 0)
        layout(w, line1 + below + (s?.height ?: 0)) {
            t.place(0, (line1 - t.height) / 2)
            if (v != null) { if (inline) v.place(w - v.width, (line1 - v.height) / 2) else v.place(0, line1) }
            s?.place(0, line1 + below)
        }
    }
}

@Composable
fun SettingsChevron() = Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = LcTheme.colors.iconSecondary)


/** − value + stepper (e.g. retries, step limit). */
@Composable
fun Stepper(value: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LcIconButton(Icons.Rounded.Remove, stringResource(R.string.ui_decrease), { onMinus?.invoke() },
            variant = IconButtonVariant.Tonal, size = IconButtonSize.Xs, enabled = onMinus != null)
        Text(value, style = LcTheme.text.bodyStrong, color = LcTheme.colors.textPrimary, modifier = Modifier.width(36.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        LcIconButton(Icons.Rounded.Add, stringResource(R.string.ui_increase), { onPlus?.invoke() },
            variant = IconButtonVariant.Tonal, size = IconButtonSize.Xs, enabled = onPlus != null)
    }
}

// ---------------- NavDrawerContent + AdaptiveScaffold ----------------

/** Drawer sheet: modal (<840dp, 320dp) or permanent (≥840dp, 272dp) on surfaceNav. */
@Composable
fun NavDrawerContent(permanent: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LcTheme.colors
    if (permanent) {
        PermanentDrawerSheet(modifier.width(272.dp), drawerContainerColor = c.surfaceNav, drawerContentColor = c.textPrimary, content = content)
    } else {
        ModalDrawerSheet(
            modifier.width(320.dp), drawerShape = androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
            drawerContainerColor = c.surfaceNav, drawerContentColor = c.textPrimary, drawerTonalElevation = 0.dp, content = content,
        )
    }
}

/** Width class used by the app scaffold (C-06, B-02): expanded = ≥ 840dp. */
fun isExpandedWidth(width: Dp): Boolean = width >= 840.dp

/**
 * Pane layout: compact = [main] only (list is a drawer, browser a sheet — owned by the screen);
 * expanded = list | main | side panels with 12dp outer margin and gaps on surfaceNav.
 */
@Composable
fun AdaptiveScaffold(
    modifier: Modifier = Modifier,
    listOpen: Boolean = true,
    list: (@Composable () -> Unit)? = null,
    side: (@Composable () -> Unit)? = null,
    main: @Composable () -> Unit,
) {
    val c = LcTheme.colors
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (!isExpandedWidth(maxWidth)) {
            Box(Modifier.fillMaxSize().background(c.surface)) { main() }
        } else {
            Row(Modifier.fillMaxSize().background(c.surfaceNav).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (list != null && listOpen) Box(Modifier.width(272.dp).fillMaxHeight()) { list() }
                Box(Modifier.weight(1f).fillMaxHeight().background(c.surface, LcRadius.xl).clip(LcRadius.xl)) { main() }
                if (side != null) Box(Modifier.width(460.dp).fillMaxHeight().background(c.surfaceRaised, LcRadius.xl).clip(LcRadius.xl)) { side() }
            }
        }
    }
}

