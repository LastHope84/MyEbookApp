# Path-qualified visibility lab — experimental, not a release

The existing row-hiding and root-detach entry points remain disabled. Folder-removal and restore UI remains disabled. **This lab does not yet hide existing library books.** It does not delete or change book records, reading progress, shelf associations, ebook files or grants. The old root hide SQL remains experimental and must not be used directly.

## What changed

* Migration 11→12 adds root-qualified JSON-array directory-path exclusions, starting empty; there is no backfill. The UI-disabled DAO checks the configured-root set, scan generation, root grant and every previously observed parent/directory edge in the same Room transaction before inserting an exclusion. It invalidates pending scan batches. Restore is root/path specific and also invalidates pending batches. An exclusion of the root directory path suppresses the entire root, without removing that root from preferences.
* Both the scan walk and batch transaction check exact, component-by-component path prefixes. An excluded multiparent route is not reserved in the queue before an alternate route can be considered. Older identity-wide and global exclusions retain their old behavior and can still suppress more routes. The path facility has no user-facing caller.
* The standalone membership decision can now model an explicitly selected root-qualified path and rejects missing path evidence through `decidePathQualified`. It preserves other known routes and unknown provenance. It does not write visibility and is not called by the application.
* The existing failing Room INSERT-query return type is changed from Int to Unit. The still-disabled legacy entry point counts rows before/after its insert to compute a count without relying on an INSERT query returning an Int.

## Remaining blocker

A historical observation or absence of a route is not proof that all app-owned memberships have been collected. Scan walking visits a directory ID at most once and old rows can have untracked independent history. There is not yet an authoritative, complete, per-book route-membership ledger with verified forward-only creation, independent-import coverage, root-set/generation lifecycle, ambiguity handling, and safe invalidation. Thus there is no evidence issuer capable of satisfying the model, no new reversible hiding transaction, and no restoration/recomputation of hidden rows across overlapping exclusions. Do not enable the UI or call old hiding DAO primitives. Path evidence also comes from historical sightings, not an atomic provider check; it is only a provisional future-scan suppression experiment.

`invalidateScanInventory()` currently unhides all old experimental hidden rows, and ordinary scans can unhide matches; these must be redesigned before durable hiding is possible. Saved preferences versus Room, legacy identity-wide exclusions, queue/restore behavior and crash recovery also need reconciliation.

Host tests execute the actual migration and DAO read/delete SQL in SQLite and test a Python path model plus source contracts. The Kotlin decision test was added but not run here. No Android/Room compiler build, migration validation, instrumented/provider tests, process-death, concurrency or device tests were possible in this environment. Passing host tests are not a safety or device certification.
