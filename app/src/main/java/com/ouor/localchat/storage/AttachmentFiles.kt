package com.ouor.localchat.storage

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only owner of filesDir/attachments paths: {id} original, {id}.txt extracted text,
 * {id}.req.jpg request image (architecture 3.3). Also runs attachment GC.
 */
@Singleton
class AttachmentFiles @Inject constructor() {
    // TODO(track D): file paths, write/read, GC of unreferenced files.
}
