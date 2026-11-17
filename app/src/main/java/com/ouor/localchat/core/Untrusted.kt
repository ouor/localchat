package com.ouor.localchat.core

/**
 * The B-S5 page-content boundary tag. In core so chat.context.Untrusted (wraps page text) and llm.AnswerFilter
 * (strips it from answers; llm may depend on core only) share one literal.
 */
const val UNTRUSTED_TAG = "untrusted_page_content"
