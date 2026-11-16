package com.ouor.localchat.tools.browser

import android.graphics.Bitmap
import com.ouor.localchat.R
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.params
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream

object Screenshot : Tool {
    override val name = "screenshot"
    override val groups = setOf(ToolGroup.BROWSER)
    override val requiresVision = true
    override val labelRes = R.string.tool_screenshot
    override val description =
        "Image of the visible part of your tab. Costly: use it only when layout or images matter; " +
            "snapshot is enough to find and operate elements."
    override val parameters = params { tabIdParam() }

    override fun argSummary(args: JsonObject) = ""

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val (tab, bitmap) = onMain {
            val tab = ctx.tabFor(args)
            tab.awaitLoaded(LOAD_TIMEOUT)
            tab to (tab.capture() ?: throw ToolError("The tab is not on screen, so no screenshot can be taken. Use snapshot instead."))
        }
        val png = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        }
        val id = ctx.saveArtifact("screenshot-${System.currentTimeMillis()}.png", "image/png", png)
        return ToolOutcome("Screenshot attached. ${pageLine(tab)}", artifactIds = listOf(id), imageForModelId = id)
    }
}
