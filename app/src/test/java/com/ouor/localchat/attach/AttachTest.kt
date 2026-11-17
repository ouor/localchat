package com.ouor.localchat.attach

import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.CapSource
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.core.model.Sourced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class AttachTest {

    @Test
    fun `allowed list is the chat-demo list`() {
        assertTrue(AllowedTypes.isAllowed("Report.PDF"))
        assertTrue(AllowedTypes.isAllowed("a.tar.docx"))
        assertTrue(AllowedTypes.isAllowed("main.kt"))
        assertFalse(AllowedTypes.isAllowed("photo.heic"))
        assertFalse(AllowedTypes.isAllowed(".bashrc"))
        assertFalse(AllowedTypes.isAllowed("README"))
    }

    @Test
    fun `failure counts list every reason of one message, not just the first (FB4 E-4)`() {
        fun a(id: String, state: PrepState) = Attachment(id, "$id.x", "x/y", 1, AttachmentKind.DOCUMENT, state, true, createdAt = 0)
        val list = listOf(
            a("big", PrepState.Failed(PrepFailure.TOO_LARGE)),
            a("ok", PrepState.Ready),
            a("bin", PrepState.Failed(PrepFailure.UNSUPPORTED_TYPE)),
            a("doc", PrepState.Failed(PrepFailure.LEGACY_OFFICE)),
            a("bin2", PrepState.Failed(PrepFailure.UNSUPPORTED_TYPE)),
        )
        assertEquals(
            listOf(PrepFailure.TOO_LARGE to 1, PrepFailure.UNSUPPORTED_TYPE to 2, PrepFailure.LEGACY_OFFICE to 1),
            PrepFailure.counts(list).toList(),
        )
        assertTrue(PrepFailure.counts(listOf(a("ok", PrepState.Ready))).isEmpty())
    }

    @Test
    fun `validate checks count, readiness and image input`() {
        fun a(id: String, kind: AttachmentKind = AttachmentKind.DOCUMENT, state: PrepState = PrepState.Ready, original: Boolean = true) =
            Attachment(id, "$id.x", "x/y", 1, kind, state, original, createdAt = 0)
        val noImages = caps(imageInput = false)
        val list = listOf(a("img", AttachmentKind.IMAGE), a("old", AttachmentKind.IMAGE, original = false), a("wait", state = PrepState.Preparing), a("doc"))
        val issues = Validation.check(list, noImages).issues
        assertEquals(
            listOf(ValidationIssue(ValidationIssue.Kind.IMAGES_UNSUPPORTED, "img"), ValidationIssue(ValidationIssue.Kind.NOT_READY, "wait")),
            issues,
        )
        assertTrue(Validation.check(listOf(a("img", AttachmentKind.IMAGE)), caps(imageInput = true)).ok)
        val many = (1..51).map { a("d$it") }
        assertEquals(listOf(ValidationIssue(ValidationIssue.Kind.TOO_MANY, null)), Validation.check(many, noImages).issues)
    }

    @Test
    fun `copy hashes and enforces the size cap`() {
        val dest = File.createTempFile("copy", ".bin").apply { deleteOnExit() }
        val (size, sha) = Copy.withSha256(ByteArrayInputStream("abc".encodeToByteArray()), dest, 10)!!
        assertEquals(3, size)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha)
        assertNull(Copy.withSha256(ByteArrayInputStream(ByteArray(11)), dest, 10))
    }

    @Test
    fun `downscale target keeps aspect and never upscales`() {
        assertEquals(2048 to 1536, ImageDownscaler.fit(4000, 3000, 2048))
        assertEquals(1024 to 2048, ImageDownscaler.fit(2000, 4000, 2048))
        assertEquals(800 to 600, ImageDownscaler.fit(800, 600, 2048))
        assertEquals(1 to 2048, ImageDownscaler.fit(1, 10000, 2048))
    }

    private fun caps(imageInput: Boolean) = ModelCapabilities(
        toolCalling = Sourced(true, CapSource.DEFAULT),
        imageInput = Sourced(imageInput, CapSource.DEFAULT),
        reasoning = Sourced(null, CapSource.DEFAULT),
        contextWindow = Sourced(null, CapSource.UNKNOWN),
        maxOutputTokens = Sourced(null, CapSource.UNKNOWN),
    )
}
