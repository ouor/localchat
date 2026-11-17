package com.ouor.localchat.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics

/*
 * P-STABLE building blocks (docs/dev/design/principles.md): controls the user operates never move when something
 * appears, disappears or changes its text.
 */

/** Keeps the element's space but draws nothing and hides it from TalkBack. [hidden] false = shown as usual. */
fun Modifier.reserved(hidden: Boolean = true): Modifier = if (hidden) alpha(0f).clearAndSetSemantics {} else this

/**
 * A slot as large as the largest of [reserve] (laid out invisibly, see [reserved]), with [content] drawn on top,
 * so switching between those variants never resizes the slot or moves what is next to it.
 */
@Composable
fun StableSlot(
    reserve: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier, contentAlignment) {
        Box(Modifier.reserved(), contentAlignment) { reserve() }
        content()
    }
}
