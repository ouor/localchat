package com.ouor.localchat.attach

import androidx.core.content.FileProvider

/**
 * Shares attachment originals (BUG-17). An own subclass so no library's FileProvider entry can clash
 * in the merged manifest. R by full name: ArchitectureTest reads a root-package import as the app layer.
 */
class AttachmentFileProvider : FileProvider(com.ouor.localchat.R.xml.attachment_paths)
