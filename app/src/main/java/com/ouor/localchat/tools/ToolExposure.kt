package com.ouor.localchat.tools

import com.ouor.localchat.core.model.ModelCapabilities

/**
 * Which tools the model sees (architecture 6.3, plan 3.8.1). Pure; ChatEngine.exposurePreview uses the same
 * function for the P-05 toggle state. Only exposed tools run: ToolExecutor answers a call to any other listed tool
 * TOOL_OFF ([listedTools]) and to an unlisted name "Unknown tool".
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

    /**
     * FB3 QA M1: the tools a request lists: [exposed] plus those the history called ([historyNames], looked up with
     * [byName]; unknown names left out). A history call without its definition in the request made DeepSeek write
     * further calls as text. Listing is not exposure: only [exposed] may run.
     */
    fun listedTools(
        exposed: List<Tool>,
        historyNames: Collection<String>,
        caps: ModelCapabilities,
        byName: (String) -> Tool? = ToolCatalog::byName,
    ): List<Tool> =
        if (!caps.toolCalling.value) exposed
        else (exposed + historyNames.mapNotNull(byName)).distinctBy { it.name }
}
