package com.ouor.localchat.policy

import android.content.Context
import com.ouor.localchat.agent.R
import com.ouor.localchat.browser.script.Located
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The user confirms before the AI spends money or deletes an account (browser-layers 4-2, S14), nothing else. A
 * wrong catch costs the user a tap, so a miss is the better error: only button-like targets whose label says it,
 * links whose whole label is the action, Enter into a form whose submit button says it, and payment-provider frames.
 * The word lists live only here.
 */
class MoneyConfirmPolicy(private val context: Context) : ToolPolicy {

    override suspend fun before(call: PolicyCall): Verdict {
        val ref = (call.args["ref"] as? JsonPrimitive)?.contentOrNull ?: return Verdict.Allow
        val (kind, label) = when (call.tool) {
            "click" -> call.describe(ref)?.let { at -> forClick(at)?.let { it to labelOf(at) } }
            "type" -> {
                val text = (call.args["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                call.describe(ref)?.takeIf { submits(it, text) }?.formSubmitLabel?.let { l -> kindOf(l)?.let { it to l } }
            }
            else -> null
        } ?: return Verdict.Allow
        val title = context.getString(if (kind == Kind.ACCOUNT) R.string.agent_account_title else R.string.agent_money_title)
        return Verdict.Confirm(ApprovalRequest(title, label, call.captureElement(ref), call.tab?.url))
    }

    enum class Kind { MONEY, ACCOUNT }

    companion object {
        private const val MAX_LABEL = 60

        private val MONEY_EN = setOf("pay", "purchase", "buy", "order", "checkout", "transfer", "withdraw")
        private val MONEY_KO = listOf("결제", "구매", "주문", "송금", "이체", "충전")
        private val ACCOUNT_EN = listOf("delete account", "delete my account", "close account", "close my account", "deactivate account")
        private val ACCOUNT_KO = listOf("탈퇴", "계정 삭제")

        /** Looking things up, not doing them: "주문 내역", "Order status". */
        private val LOOKUP = listOf("내역", "조회", "history", "status", "details")

        /** A link counts only when its whole label is one of these. */
        private val LINK_ACTIONS = setOf(
            "결제하기", "구매하기", "주문하기", "송금하기", "pay now", "buy now", "place order", "checkout", "회원 탈퇴", "delete account",
        )

        /** Payment pages a shop embeds (card forms, pay buttons): a click inside one is a payment. */
        private val PAYMENT_HOSTS = listOf(
            "stripe.com", "paypal.com", "pay.google.com", "adyen.com", "braintreegateway.com", "checkout.com",
            "pay.naver.com", "kakaopay.com", "tosspayments.com", "toss.im", "inicis.com", "nicepay.co.kr", "kcp.co.kr", "portone.io", "iamport.kr",
        )

        private val SPLIT = Regex("""[\s\p{P}\p{S}]+""")

        fun labelOf(at: Located): String = at.info.name?.trim().orEmpty().take(MAX_LABEL)

        /** What a click on [at] would do, null = nothing to confirm. */
        fun forClick(at: Located): Kind? {
            if (paymentFrame(at.frame)) return Kind.MONEY
            val i = at.info
            val label = labelOf(at)
            val button = i.tag == "button" || i.role == "button" || (i.tag == "input" && i.type in setOf("submit", "button", "image"))
            val link = i.role == "link" || i.tag == "a"
            return when {
                button -> kindOf(label)
                link -> norm(label).takeIf { it in LINK_ACTIONS }?.let { if (it == "회원 탈퇴" || it == "delete account") Kind.ACCOUNT else Kind.MONEY }
                else -> null
            }
        }

        /** Typing [text] into [at] submits its form: a search box does by itself, a one-line field on a final newline. */
        fun submits(at: Located, text: String): Boolean =
            at.info.search || (text.endsWith("\n") && at.info.tag != "textarea")

        /** Word by word (Korean: a word containing one of the words), lookups left out. */
        fun kindOf(label: String): Kind? {
            val l = norm(label.take(MAX_LABEL))
            if (l.isEmpty()) return null
            val words = l.split(SPLIT).filter { it.isNotEmpty() }
            if (LOOKUP.any { w -> words.any { it == w || (w.first() > '\u007f' && w in it) } }) return null
            val spaced = " ${words.joinToString(" ")} "
            if (ACCOUNT_EN.any { " $it " in spaced } || ACCOUNT_KO.any { it in l }) return Kind.ACCOUNT
            if (words.any { it in MONEY_EN } || words.any { w -> MONEY_KO.any { it in w } }) return Kind.MONEY
            return null
        }

        private fun norm(s: String) = s.trim().lowercase().replace(Regex("""\s+"""), " ")

        private fun paymentFrame(frame: String?): Boolean {
            val host = frame?.let { SensitiveUrls.hostOf(it) } ?: return false
            return PAYMENT_HOSTS.any { host == it || host.endsWith(".$it") }
        }
    }
}
