package com.ouor.localchat.tools

import com.ouor.localchat.core.model.ModelCapabilities

/**
 * Which tools the model sees (architecture 6.3, plan 3.8.1). Pure; ChatEngine.exposurePreview uses the same
 * function for the P-05 toggle state, and ToolExecutor rejects calls to names outside this list.
 */
object ToolExposure {
    fun exposedTools(caps: ModelCapabilities, searchOn: Boolean, browserOn: Boolean): List<Tool> {
        // Both switches off means no tools at all; pasted links are then prefetched by the app (S-03).
        if (!caps.toolCalling.value) return emptyList()
        val groups = buildSet {
            if (searchOn) add(ToolGroup.SEARCH)
            if (browserOn) add(ToolGroup.BROWSER)
        }
        return ToolCatalog.ALL.filter { t -> t.groups.any(groups::contains) && (!t.requiresVision || caps.imageInput.value) }
    }
}
