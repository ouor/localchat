package com.ouor.localchat.di

import com.ouor.localchat.chat.AttachmentPort
import com.ouor.localchat.chat.CatalogToolPort
import com.ouor.localchat.chat.ChatStore
import com.ouor.localchat.chat.ClientLlmPort
import com.ouor.localchat.chat.LlmPort
import com.ouor.localchat.chat.ModelPort
import com.ouor.localchat.chat.ProviderModelPort
import com.ouor.localchat.chat.SessionRepository
import com.ouor.localchat.chat.StoredAttachmentPort
import com.ouor.localchat.chat.ToolPort
import com.ouor.localchat.agent.BrowserPort
import com.ouor.localchat.agent.EngineBrowserPort
import com.ouor.localchat.browser.BrowserEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Track B bindings. */
@Module
@InstallIn(SingletonComponent::class)
interface ChatModule {
    @Binds fun chatStore(impl: SessionRepository): ChatStore
    @Binds fun llmPort(impl: ClientLlmPort): LlmPort
    @Binds fun modelPort(impl: ProviderModelPort): ModelPort
    @Binds fun toolPort(impl: CatalogToolPort): ToolPort
    @Binds fun attachmentPort(impl: StoredAttachmentPort): AttachmentPort

    companion object {
        @Provides fun browserPort(engine: BrowserEngine): BrowserPort = EngineBrowserPort(engine)
    }
}
