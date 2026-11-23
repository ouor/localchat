package com.ouor.localchat.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.annotation.MainThread

/**
 * The least an app needs to show a tab without a drawer of its own (for ask_user, say): put this view full size in
 * an Activity and [bind] it. It becomes the engine's host container ([BrowserEngine.attachHost]), so every tab's
 * WebView lives in it at real size, and the bound tab is drawn on top ([BrowserEngine.show]); the others stay parked.
 * Taken off the window, it parks the tab and lets the WebViews go ([BrowserEngine.detachHost]).
 */
class BrowserTabHost @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private var engine: BrowserEngine? = null
    private var shown: Int? = null

    init {
        // The window's first focus lands here, not on a WebView: a WebView taking focus scrolls its page back (QA B1),
        // which would move an element under the AI's tap. A user's touch still focuses the page.
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
    }

    /** Hosts [engine]'s tabs and shows [tabId]; null shows none. */
    @MainThread
    fun bind(engine: BrowserEngine, tabId: Int?) {
        if (this.engine !== engine) {
            unbind()
            engine.attachHost(activity(), this)
            this.engine = engine
        }
        if (tabId == shown) return
        shown?.let(engine::park)
        shown = tabId
        tabId?.let(engine::show)
    }

    @MainThread
    fun unbind() {
        val e = engine ?: return
        shown?.let(e::park)
        e.detachHost(activity())
        engine = null
        shown = null
    }

    override fun onDetachedFromWindow() {
        unbind()
        super.onDetachedFromWindow()
    }

    private fun activity(): Activity {
        var c = context
        while (c !is Activity) c = (c as? ContextWrapper)?.baseContext ?: error("BrowserTabHost needs an Activity context")
        return c
    }
}
