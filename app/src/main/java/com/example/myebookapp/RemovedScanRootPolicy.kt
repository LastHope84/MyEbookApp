package com.example.myebookapp

import android.net.Uri
import android.provider.DocumentsContract

/** Exclude the removed directory only along routes observed under each scanned grant.
 * Opaque IDs are compared only within one provider authority, never as path strings.
 * An independently configured grant whose own root is that same document still works.
 * This policy cannot catch provider aliases or alternate routes that bypass the directory. */
internal fun removedRootBlocks(rootUri: String, authority: String, removed: List<RemovedScanRoot>): Set<String> {
    val rootDocument = DocumentsContract.getTreeDocumentId(Uri.parse(rootUri))
    return removed.asSequence().filter { it.authority == authority }
        .filter { it.rootUri == rootUri || it.documentId != rootDocument }
        .map { it.documentId }.toSet()
}
