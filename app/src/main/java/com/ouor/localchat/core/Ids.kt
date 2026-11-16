package com.ouor.localchat.core

import java.util.UUID

object Ids {
    fun new(): String = UUID.randomUUID().toString()
}
