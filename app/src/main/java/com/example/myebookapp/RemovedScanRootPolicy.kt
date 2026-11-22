package com.example.myebookapp

/** Tombstones apply only to their own configured grant. Other overlapping roots retain their
 * independent routes; IDs are opaque and cannot establish ancestry or ownership. */
internal fun removedRootBlocks(rootUri: String, authority: String, removed: List<RemovedScanRoot>): Set<String> =
    removed.asSequence().filter { it.rootUri == rootUri && it.authority == authority }
        .map { it.documentId }.toSet()
