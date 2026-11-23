package com.ouor.localchat.core.model

import com.ouor.localchat.core.AppJson
import com.ouor.localchat.tools.Source
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The DB body column and backups share this encoding; type tags are part of the backup format. */
class SerializationTest {

    private val node = MessageNode(
        id = "a", sessionId = "s", parentId = "u", selectedChildId = null,
        role = Role.ASSISTANT, createdAt = 1,
        body = MessageBody.Assistant(
            blocks = listOf(
                ThinkingBlock("why", 0, 1, 2),
                ToolCallBlock("c1", "click", """{"ref":"e1"}""", 0, ToolCallState.DONE, 3, 4),
                ToolResultBlock("c1", 0, "ok", false, sources = listOf(Source(1, "https://a.b", "A"))),
                TextBlock("done", 1),
            ),
            status = ReplyStatus.DONE,
            finishReason = "stop",
            model = ModelRef("e", "deepseek-flash", "Flash"),
            options = GenOptions(reasoning = true, browser = true),
            usage = Usage(10, 5),
        ),
    )

    @Test
    fun `message node round-trips`() {
        val text = AppJson.encodeToString(MessageNode.serializer(), node)
        assertEquals(node, AppJson.decodeFromString(MessageNode.serializer(), text))
    }

    @Test
    fun `type tags are stable`() {
        val body = AppJson.encodeToJsonElement(MessageBody.serializer(), node.body).jsonObject
        assertEquals("assistant", body["type"]!!.jsonPrimitive.content)
        val tags = AppJson.encodeToJsonElement(MessageBody.serializer(), node.body).jsonObject["blocks"]
            .toString()
        listOf("thinking", "tool_call", "tool_result", "text").forEach {
            assert(tags.contains("\"type\":\"$it\"")) { "missing tag $it" }
        }
    }

    @Test
    fun `unknown keys and missing defaults decode`() {
        val s = AppJson.decodeFromString(AppSettings.serializer(), """{"maxSteps":40,"futureField":1}""")
        assertEquals(AppSettings(maxSteps = 40), s)
    }
}
