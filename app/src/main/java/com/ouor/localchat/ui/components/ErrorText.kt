package com.ouor.localchat.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ouor.localchat.R
import com.ouor.localchat.core.model.ChatError
import com.ouor.localchat.core.model.ChatErrorKind

/** What the reply-slot error offers (architecture 10.1: the one mapping place for ChatError.kind). */
enum class ErrorAction(@StringRes val label: Int) {
    ChangeKey(R.string.ui_err_action_change_key),
    TopUp(R.string.ui_err_action_top_up),
    Retry(R.string.ui_err_action_retry),
    OpenModelSettings(R.string.ui_err_action_model_settings),
    SummarizeNow(R.string.ui_summarize_now),
}

data class ErrorPresentation(@StringRes val title: Int, @StringRes val body: Int, val action: ErrorAction)

/** [usedOptionalFeatures]: the failed request used tools/images/reasoning → P-05 hint instead of plain retry. */
fun errorPresentation(kind: ChatErrorKind, usedOptionalFeatures: Boolean = false): ErrorPresentation = when (kind) {
    ChatErrorKind.AUTH -> ErrorPresentation(R.string.ui_err_auth_title, R.string.ui_err_auth_body, ErrorAction.ChangeKey)
    ChatErrorKind.BALANCE -> ErrorPresentation(R.string.ui_err_balance_title, R.string.ui_err_balance_body, ErrorAction.TopUp)
    ChatErrorKind.RATE_LIMITED -> ErrorPresentation(R.string.ui_err_rate_title, R.string.ui_err_retried_body, ErrorAction.Retry)
    ChatErrorKind.SERVER_BUSY -> ErrorPresentation(R.string.ui_err_busy_title, R.string.ui_err_retried_body, ErrorAction.Retry)
    ChatErrorKind.TIMEOUT -> ErrorPresentation(R.string.ui_err_timeout_title, R.string.ui_err_retried_body, ErrorAction.Retry)
    ChatErrorKind.NETWORK -> ErrorPresentation(R.string.ui_err_network_title, R.string.ui_err_network_body, ErrorAction.Retry)
    ChatErrorKind.EMPTY_RESPONSE -> ErrorPresentation(R.string.ui_err_empty_title, R.string.ui_err_empty_body, ErrorAction.Retry)
    ChatErrorKind.CONTEXT_OVERFLOW -> ErrorPresentation(R.string.ui_err_context_title, R.string.ui_err_context_body, ErrorAction.SummarizeNow)
    ChatErrorKind.BAD_REQUEST ->
        if (usedOptionalFeatures) ErrorPresentation(R.string.ui_err_feature_title, R.string.ui_err_feature_body, ErrorAction.OpenModelSettings)
        else ErrorPresentation(R.string.ui_err_bad_request_title, R.string.ui_err_bad_request_body, ErrorAction.Retry)
    ChatErrorKind.PROTOCOL -> ErrorPresentation(R.string.ui_err_protocol_title, R.string.ui_err_protocol_body, ErrorAction.Retry)
    // F-11 (strings in strings_b until UI takes them over).
    ChatErrorKind.MODEL_UNAVAILABLE -> ErrorPresentation(R.string.chat_err_model_unavailable_title, R.string.chat_err_model_unavailable_body, ErrorAction.OpenModelSettings)
    ChatErrorKind.CONFIG -> ErrorPresentation(R.string.chat_err_config_title, R.string.chat_err_config_body, ErrorAction.OpenModelSettings)
}

/** ErrorCard for a stored [ChatError]; the HTTP status is appended to the body ("(429)"). */
@Composable
fun ChatErrorCard(error: ChatError, onAction: (ErrorAction) -> Unit, modifier: Modifier = Modifier, usedOptionalFeatures: Boolean = false) {
    val p = errorPresentation(error.kind, usedOptionalFeatures)
    val body = stringResource(p.body) + (error.httpStatus?.let { " ($it)" } ?: "")
    ErrorCard(stringResource(p.title), modifier, detail = body, actionLabel = stringResource(p.action.label), onAction = { onAction(p.action) })
}

/** E-04 refusal: copy is the only action. */
@Composable
fun RefusalCard(onCopy: () -> Unit, modifier: Modifier = Modifier) {
    ErrorCard(stringResource(R.string.ui_refusal_title), modifier, detail = stringResource(R.string.ui_refusal_body), actionLabel = stringResource(R.string.ui_copy), onAction = onCopy)
}
