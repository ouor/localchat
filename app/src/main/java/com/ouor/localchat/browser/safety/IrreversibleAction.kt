package com.ouor.localchat.browser.safety

/**
 * B-S2: which AI actions need the user's approval (SC-13) every time. Keyword lists live only here.
 * GET forms (search boxes) need no approval; script-driven POSTs cannot be detected before a click (R3).
 */
object IrreversibleAction {

    enum class Reason {
        /** Submitting a POST form (click on its submit control, Enter inside it). */
        POST_FORM,

        /** Button/link text reads like pay, order, delete, send, post, confirm ... */
        RISKY_KEYWORD,

        /** Typing into a page served over plain http (team decision 1). */
        INSECURE_INPUT,

        /** Clicking a frame whose (cross-origin) content cannot be checked, e.g. a payment widget. */
        FRAME,
    }

    private val EN = listOf(
        "pay", "payment", "purchase", "buy", "order", "checkout", "check out", "delete", "remove", "send",
        "post", "publish", "confirm", "transfer", "withdraw", "unsubscribe", "cancel subscription",
        "place order", "deactivate", "close account",
    )
    private val KO = listOf(
        "결제", "구매", "주문", "삭제", "전송", "보내기", "게시", "등록", "확인", "송금", "이체", "탈퇴", "해지",
        "제출", "구독",
    )
    private val enRegex = Regex("\\b(" + EN.joinToString("|") { Regex.escape(it).replace(" ", "\\s+") } + ")\\b", RegexOption.IGNORE_CASE)

    /** Only the start of a label is checked: a long name is page content, but a page must not hide "Pay" by padding. */
    private const val MAX_LABEL = 60

    private val CLICKABLE_ROLES = setOf("button", "link", "menuitem", "menuitemcheckbox", "menuitemradio", "tab", "option")

    fun reasonForClick(e: ElementInfo): Reason? {
        if (SensitiveFields.isFrame(e)) return Reason.FRAME
        if (isSubmitControl(e) && e.formMethod == "post") return Reason.POST_FORM
        val label = e.name?.trim().orEmpty().take(MAX_LABEL)
        val actionable = e.role in CLICKABLE_ROLES || e.tag == "button" || e.tag == "a" || e.tag == "input"
        if (actionable && label.isNotEmpty() && hasRiskyKeyword(label)) return Reason.RISKY_KEYWORD
        return null
    }

    /** type's Enter in a POST form submits it; Enter elsewhere is free, and field labels are not keyword-checked. */
    fun reasonForEnter(focused: ElementInfo?): Reason? =
        if (focused?.formMethod == "post" && focused.tag != "textarea") Reason.POST_FORM else null

    fun hasRiskyKeyword(label: String): Boolean = enRegex.containsMatchIn(label) || KO.any { it in label }

    private fun isSubmitControl(e: ElementInfo): Boolean = when (e.tag) {
        "button" -> e.type == null || e.type == "" || e.type == "submit"
        "input" -> e.type == "submit" || e.type == "image"
        else -> false
    }
}
