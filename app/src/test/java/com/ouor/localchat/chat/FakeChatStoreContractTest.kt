package com.ouor.localchat.chat

/** The agent tests' store keeps the same rules as SessionRepository (AR F-08). */
class FakeChatStoreContractTest : ChatStoreContract() {
    override fun store(): ChatStore = FakeChatStore()
}
