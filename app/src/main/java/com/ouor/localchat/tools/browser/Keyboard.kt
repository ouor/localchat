package com.ouor.localchat.tools.browser

import android.view.KeyEvent
import com.ouor.localchat.R
import com.ouor.localchat.browser.safety.IrreversibleAction
import com.ouor.localchat.browser.script.FocusResult
import com.ouor.localchat.browser.script.PageScript
import com.ouor.localchat.tools.ApprovalMode
import com.ouor.localchat.tools.Tool
import com.ouor.localchat.tools.ToolContext
import com.ouor.localchat.tools.ToolError
import com.ouor.localchat.tools.ToolGroup
import com.ouor.localchat.tools.ToolOutcome
import com.ouor.localchat.tools.optString
import com.ouor.localchat.tools.params
import com.ouor.localchat.tools.string
import kotlinx.serialization.json.JsonObject

/**
 * Native key events to the focused element (trusted). Any key into a password/OTP/card field or a frame is
 * refused (B-S1, SE 1); Enter/Space on a control that a click would need approval for asks first (B-S2, SE 8).
 */
object Keyboard : Tool {
    override val name = "keyboard"
    override val groups = setOf(ToolGroup.BROWSER)
    override val approval = ApprovalMode.CONDITIONAL
    override val labelRes = R.string.tool_keyboard
    override val description =
        "Press a key on the focused element of your tab, e.g. Enter, Tab, Escape, ArrowDown, Backspace, " +
            "Control+A. Focus a field first with type or click. Enter in a form that posts data asks the user first."
    override val parameters = params {
        string("key", "Key name, optionally with modifiers joined by +, e.g. \"Enter\", \"Shift+Tab\", \"Control+A\".", required = true)
        tabIdParam()
    }

    override fun argSummary(args: JsonObject) = args.optString("key").orEmpty()

    override suspend fun run(args: JsonObject, ctx: ToolContext): ToolOutcome {
        val key = args.string("key")
        val (code, meta) = parse(key) ?: throw ToolError("Unknown key '$key'. Use names like Enter, Tab, Escape, ArrowDown, Backspace, a, Control+A.")
        return onMain {
            val tab = ctx.tabFor(args)
            val focus = PageScript.call<FocusResult>(tab, "focusInfo")
            guardInput(focus.info, focus.sensitive)
            val reason = IrreversibleAction.reasonForKey(code == KeyEvent.KEYCODE_ENTER, code == KeyEvent.KEYCODE_SPACE, focus.info)
            if (!ctx.guardActivate(name, tab, reason, "$key on ${focus.info?.label()}")) return@onMain ToolOutcome.DENIED
            tab.key(code, meta)
            ToolOutcome("Pressed $key. ${settle(tab)}", summary = key)
        }
    }

    private val NAMED = mapOf(
        "enter" to KeyEvent.KEYCODE_ENTER, "return" to KeyEvent.KEYCODE_ENTER, "tab" to KeyEvent.KEYCODE_TAB,
        "escape" to KeyEvent.KEYCODE_ESCAPE, "esc" to KeyEvent.KEYCODE_ESCAPE, "backspace" to KeyEvent.KEYCODE_DEL,
        "delete" to KeyEvent.KEYCODE_FORWARD_DEL, "del" to KeyEvent.KEYCODE_FORWARD_DEL, "space" to KeyEvent.KEYCODE_SPACE,
        " " to KeyEvent.KEYCODE_SPACE, "arrowup" to KeyEvent.KEYCODE_DPAD_UP, "up" to KeyEvent.KEYCODE_DPAD_UP,
        "arrowdown" to KeyEvent.KEYCODE_DPAD_DOWN, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "arrowleft" to KeyEvent.KEYCODE_DPAD_LEFT, "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "arrowright" to KeyEvent.KEYCODE_DPAD_RIGHT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "home" to KeyEvent.KEYCODE_MOVE_HOME, "end" to KeyEvent.KEYCODE_MOVE_END,
        "pageup" to KeyEvent.KEYCODE_PAGE_UP, "pagedown" to KeyEvent.KEYCODE_PAGE_DOWN,
    )
    private val MODIFIERS = mapOf(
        "control" to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON), "ctrl" to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
        "shift" to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
        "alt" to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
        "meta" to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON), "cmd" to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
    )

    /** "Control+Shift+A" -> (KEYCODE_A, ctrl|shift meta). Null when unknown. */
    fun parse(spec: String): Pair<Int, Int>? {
        val parts = if (spec == "+") listOf("+") else spec.split('+').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var meta = 0
        for (m in parts.dropLast(1)) meta = meta or (MODIFIERS[m.lowercase()] ?: return null)
        val k = parts.last()
        val code = NAMED[k.lowercase()] ?: when {
            k.length == 1 && k[0].lowercaseChar() in 'a'..'z' -> KeyEvent.KEYCODE_A + (k[0].lowercaseChar() - 'a')
            k.length == 1 && k[0] in '0'..'9' -> KeyEvent.KEYCODE_0 + (k[0] - '0')
            k.length in 2..3 && k[0].lowercaseChar() == 'f' && k.drop(1).toIntOrNull() in 1..12 -> KeyEvent.KEYCODE_F1 + k.drop(1).toInt() - 1
            else -> return null
        }
        return code to meta
    }
}
