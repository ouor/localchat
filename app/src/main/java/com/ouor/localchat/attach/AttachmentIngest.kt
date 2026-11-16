package com.ouor.localchat.attach

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.ouor.localchat.attach.extract.ExtractException
import com.ouor.localchat.attach.extract.OfficeText
import com.ouor.localchat.attach.extract.PdfText
import com.ouor.localchat.attach.extract.PlainText
import com.ouor.localchat.attach.extract.TextOut
import com.ouor.localchat.core.Clock
import com.ouor.localchat.core.Ids
import com.ouor.localchat.core.model.Attachment
import com.ouor.localchat.core.model.AttachmentKind
import com.ouor.localchat.core.model.ModelCapabilities
import com.ouor.localchat.core.model.PrepState
import com.ouor.localchat.storage.AttachmentFiles
import com.ouor.localchat.storage.db.dao.AttachmentDao
import com.ouor.localchat.storage.db.toEntity
import com.ouor.localchat.storage.db.toModel
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attachment intake (public surface, architecture 10.2). Type/size failures surface as PrepState.Failed.
 * Every state is upserted to the `attachments` table before it is emitted, so the row exists before
 * any draft or message refers to the id.
 */
@Singleton
class AttachmentIngest @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: AttachmentFiles,
    private val dao: AttachmentDao,
    private val clock: Clock,
) {
    /** Source Uris of this process, to retry a failed copy. After a restart only preparation can be retried. */
    private val uris = ConcurrentHashMap<String, Uri>()

    /** Copies, checks and prepares [uri]: emits PREPARING, then READY or FAILED (I-05). */
    fun ingest(uri: Uri): Flow<Attachment> = flow {
        val resolver = context.contentResolver
        val (name, size) = queryNameAndSize(resolver, uri)
        val mime = mimeOf(resolver.getType(uri), name)
        val base = Attachment(
            id = Ids.new(), name = name, mime = mime, size = size ?: 0, kind = kindOf(mime, name),
            state = PrepState.Preparing, hasOriginal = true, createdAt = clock.now(),
        )
        uris[base.id] = uri
        emit(save(base))
        emit(save(copyAndPrepare(base, uri)))
    }.flowOn(Dispatchers.IO)

    /** Re-runs preparation of a FAILED attachment. Throws IllegalArgumentException for an unknown id. */
    fun retry(attachmentId: String): Flow<Attachment> = flow {
        val failed = requireNotNull(dao.get(attachmentId)?.toModel()) { "unknown attachment $attachmentId" }
        emit(save(failed.copy(state = PrepState.Preparing)))
        emit(save(retried(failed.copy(state = PrepState.Preparing), uris[attachmentId])))
    }.flowOn(Dispatchers.IO)

    /** Send-time check against the target model (I-03). */
    fun validate(attachments: List<Attachment>, caps: ModelCapabilities): ValidationResult = Validation.check(attachments, caps)

    /**
     * Coil model for an image tile: the downscaled request image when it exists (cheaper), else the
     * original. Image artifacts (approval previews, screenshots) have no request image and use the original.
     * null for non-images and when neither file exists (name-only rows, failed copies).
     */
    fun imageModel(a: Attachment): File? {
        val image = a.kind == AttachmentKind.IMAGE || (a.kind == AttachmentKind.ARTIFACT && a.mime.startsWith("image/"))
        if (!image) return null
        return files.requestImage(a.id).takeIf { it.isFile } ?: files.original(a.id).takeIf { it.isFile }
    }

    /**
     * Rebuilds {id}.txt / {id}.req.jpg from a stored original: backups carry originals only, so chat
     * calls this (on IO) when a restored attachment's derived file is missing. Name-only rows come back as is.
     */
    fun prepareStored(a: Attachment): Attachment = if (a.hasOriginal && files.original(a.id).isFile) prepare(a) else a

    /** Prepare again from the copied original, copy again from [uri], or give up when neither exists. */
    internal fun retried(a: Attachment, uri: Uri?): Attachment = when {
        a.sha256 != null && files.original(a.id).isFile -> prepare(a)
        uri != null -> copyAndPrepare(a, uri)
        // The Uri permission did not survive a restart.
        else -> a.failed(PrepFailure.READ_FAILED)
    }

    private suspend fun save(a: Attachment): Attachment = a.also { dao.upsert(it.toEntity()) }

    private fun copyAndPrepare(a: Attachment, uri: Uri): Attachment {
        if (!AllowedTypes.isAllowed(a.name)) return a.failed(PrepFailure.UNSUPPORTED_TYPE)
        if (a.size > AllowedTypes.MAX_BYTES) return a.failed(PrepFailure.TOO_LARGE)
        val dest = files.original(a.id)
        val copied = try {
            val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
            stream.use { Copy.withSha256(it, dest, AllowedTypes.MAX_BYTES) }
        } catch (e: IOException) {
            dest.delete()
            return a.failed(PrepFailure.READ_FAILED)
        } catch (e: SecurityException) {
            // Shared/picked Uri permission is gone (e.g. after process death).
            dest.delete()
            return a.failed(PrepFailure.READ_FAILED)
        }
        if (copied == null) {
            dest.delete()
            return a.failed(PrepFailure.TOO_LARGE)
        }
        return prepare(a.copy(size = copied.first, sha256 = copied.second))
    }

    private fun prepare(a: Attachment): Attachment = guarded(a) {
        if (a.kind == AttachmentKind.IMAGE) {
            val (w, h) = ImageDownscaler.downscale(files.original(a.id), files.requestImage(a.id))
            a.copy(state = PrepState.Ready, width = w, height = h)
        } else {
            extractText(a)
            a.copy(state = PrepState.Ready)
        }
    }

    /** Any failure of [work] ends as FAILED, never as a row stuck in PREPARING (AN 9). */
    internal fun guarded(a: Attachment, work: () -> Attachment): Attachment = try {
        work()
    } catch (e: ExtractException) {
        files.text(a.id).delete()
        a.failed(e.code)
    } catch (e: Exception) {
        // IOException (ImageDecoder.DecodeException too) and the NPE/IAE/ClassCast that PDFBox and SAX throw on broken files.
        decodeFailed(a)
    } catch (e: OutOfMemoryError) {
        decodeFailed(a)
    }

    private fun decodeFailed(a: Attachment): Attachment {
        files.requestImage(a.id).delete()
        files.text(a.id).delete()
        return a.failed(if (a.kind == AttachmentKind.IMAGE) PrepFailure.IMAGE_DECODE else PrepFailure.EXTRACT_FAILED)
    }

    private fun extractText(a: Attachment) {
        val ext = AllowedTypes.ext(a.name)
        if (ext in LEGACY_OFFICE) throw ExtractException(PrepFailure.LEGACY_OFFICE)
        val source = files.original(a.id)
        val textFile: File = files.text(a.id)
        val visible = textFile.bufferedWriter().use { w ->
            val out = TextOut(w)
            when (ext) {
                "pdf" -> {
                    PDFBoxResourceLoader.init(context)
                    PdfText.extract(source, out)
                }
                "docx", "xlsx", "pptx" -> OfficeText.extract(source, ext, out)
                else -> PlainText.extract(source, out)
            }
            out.visible
        }
        if (visible == 0) throw ExtractException(PrepFailure.NO_TEXT)
    }

    private fun queryNameAndSize(resolver: ContentResolver, uri: Uri): Pair<String, Long?> {
        val fallback = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return fallback to null
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(c::getString)
            val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            (name?.takeIf { it.isNotBlank() } ?: fallback) to size
        } ?: (fallback to null)
    }

    private fun mimeOf(reported: String?, name: String): String =
        reported?.takeIf { it != "application/octet-stream" && it.isNotBlank() }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(AllowedTypes.ext(name))
            ?: "application/octet-stream"

    private companion object {
        val LEGACY_OFFICE = setOf("doc", "xls", "ppt")

        /** SVG is XML text to the model, not a raster image. */
        fun kindOf(mime: String, name: String): AttachmentKind =
            if (mime.startsWith("image/") && AllowedTypes.ext(name) !in setOf("svg", "svgz")) AttachmentKind.IMAGE else AttachmentKind.DOCUMENT

        fun Attachment.failed(code: String) = copy(state = PrepState.Failed(code))
    }
}

/** PrepState.Failed.reason codes; ui maps each to its text. */
object PrepFailure {
    /** Extension not in the I-03 list. */
    const val UNSUPPORTED_TYPE = "unsupported_type"

    /** Over 64 MB. */
    const val TOO_LARGE = "too_large"

    /** The Uri could not be read (permission gone, provider error). Retry may help. */
    const val READ_FAILED = "read_failed"

    /** Image format the device cannot decode (psd, tga ...). */
    const val IMAGE_DECODE = "image_decode"

    /** doc / xls / ppt: not supported (I-04). */
    const val LEGACY_OFFICE = "legacy_office"

    /** Allowed extension but binary content (h5, epub ...). */
    const val UNSUPPORTED_CONTENT = "unsupported_content"

    /** Readable but no text, e.g. a scanned PDF (I-04). */
    const val NO_TEXT = "no_text"

    /** Password-protected PDF. */
    const val ENCRYPTED = "encrypted"

    /** Corrupt document. */
    const val EXTRACT_FAILED = "extract_failed"
}

data class ValidationResult(val issues: List<ValidationIssue>) {
    val ok: Boolean get() = issues.isEmpty()
}

/** [attachmentId] null = applies to the whole message. */
data class ValidationIssue(val kind: Kind, val attachmentId: String?) {
    enum class Kind { TOO_MANY, NOT_READY, IMAGES_UNSUPPORTED }
}

internal object Validation {
    fun check(attachments: List<Attachment>, caps: ModelCapabilities): ValidationResult {
        val issues = buildList {
            if (attachments.size > AllowedTypes.MAX_COUNT) add(ValidationIssue(ValidationIssue.Kind.TOO_MANY, null))
            attachments.forEach { a ->
                if (a.state != PrepState.Ready) add(ValidationIssue(ValidationIssue.Kind.NOT_READY, a.id))
                // Name-only imported images are not sent at all, so they cannot conflict (I-06).
                else if (a.kind == AttachmentKind.IMAGE && a.hasOriginal && !caps.imageInput.value) {
                    add(ValidationIssue(ValidationIssue.Kind.IMAGES_UNSUPPORTED, a.id))
                }
            }
        }
        return ValidationResult(issues)
    }
}

internal object Copy {
    /** Copies at most [max] bytes; returns (size, sha256 hex) or null when the input is larger. */
    fun withSha256(input: java.io.InputStream, dest: File, max: Long): Pair<Long, String>? {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) return null
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
        }
        return total to digest.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
