package com.ouor.localchat.policy

import com.ouor.localchat.tools.ToolOutcome

/**
 * Values of [secret] fields (password, one-time code, card) never reach the model (browser-layers 4-2, 11-2): no task
 * needs them, and they would stay on the model's server and in the chat. Plain words, so a weak model does not read
 * a mask as "nothing typed".
 */
class SecretValuePolicy : ToolPolicy {
    override fun after(call: PolicyCall, facts: CallFacts, result: ToolOutcome): ToolOutcome =
        if (MARK !in result.page) result else result.copy(page = hide(result.page))

    companion object {
        const val MARK = "[secret]"
        const val HIDDEN = "value=[hidden by the app]"
        private val VALUE = Regex(""" value="(?:[^"\\]|\\.)*"""")

        fun hide(page: String): String =
            page.lineSequence().joinToString("\n") { line -> if (MARK in line) line.replace(VALUE, " $HIDDEN") else line }
    }
}
