package com.ouor.localchat.policy

import com.ouor.localchat.browser.Answer
import com.ouor.localchat.browser.BrowserEvent
import com.ouor.localchat.browser.BrowserFacts
import kotlinx.coroutines.CoroutineScope

/** Browser events and state, whatever tool call is running or not (browser-layers 4-1). */
interface BrowserPolicy : Policy {
    /** Once at app start, with the narrow view of the browser only. */
    fun attach(browser: BrowserFacts, scope: CoroutineScope) {}

    /** Changes an event's answer; [proposed] is the earlier listener's or the default. null = keep it. Never waits. */
    fun onEvent(e: BrowserEvent, proposed: Answer): Answer? = null
}
