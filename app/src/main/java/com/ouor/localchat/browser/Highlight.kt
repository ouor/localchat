package com.ouor.localchat.browser

/**
 * B-09 highlight of the element an AI action targets. The rect is in CSS px; the overlay converts
 * with `view.width / cssViewportWidth` (architecture 7.5), not density.
 */
data class Highlight(
    val tabId: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    /** window.innerWidth when the rect was measured. */
    val cssViewportWidth: Float,
    /** String resource for the label ("click", "typing" ...). */
    val labelRes: Int,
    /** Epoch ms after which the highlight is gone. */
    val until: Long,
)
