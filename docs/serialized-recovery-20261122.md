# Corrected serialized recovery experiment — not ready for device use

Isolated source only. No production changes, install, provider access, or ebook-file operation. No APK is supplied. This replaces the earlier description of Stop retaining the queue.

## Corrections

* An old worker's cancelled reconciliation now propagates cancellation without unverifying the gate; other reconciliation failures unverify only if that worker still owns the generation. A current, genuine failure still blocks admission.
* Stop saves an empty root queue and does not advertise Resume, as in the pre-experiment scanner. It leaves already-discovered books and pending metadata in Room; normal later scans/metadata-only requests can process pending metadata. Paused and interrupted metadata-only scans with an empty root queue still have Resume. A validated old explicit-stop retained queue is cleared during restore, before admission; an invalid queue fails reconciliation instead of being silently cleared.
* A failed checkpoint commit poisons this process, cancels work, blocks checkpoint writes and reports that Stop may not have been saved. Preference cache changes are not proof of disk persistence. Process-local poison does not survive restart. No guarantee of successful durable Stop after a failed commit or process death.
* When a Stop/Pause/service interruption invalidates an asynchronous restore, it retries fresh reconciliation while admission remains closed. No stale success/error is applied; a current genuine failure remains fail-closed. Repeated controls may delay recovery; a blocked Room operation may remain blocked. A failure still has no dedicated user-facing recovery action. Restore itself never starts scanning.
* V3 staging and replay remain disconnected; pending journals fail closed. Add Folder, removal, detach, exclusion and exclusion restore remain disabled; no cross-store atomicity is claimed.

## Remaining limits and verification

The gate is process-local and cannot serialize arbitrary Room writers, grants, other processes, or the whole V3 protocol. An already-blocking provider query, in-flight Room transaction or NonCancellable metadata cleanup may finish after Stop; cancellation is not instant I/O quiescence. An old metadata status callback may still update progress after Stop. No claim covers delayed service lifecycle/start-ID callbacks, timeout, process restart, permission revocation or real disk faults. Stop cannot erase discovered books, metadata, or source ebook files. A queue from a legacy stopped state lacking an explicit stop marker is not migrated by this correction.

Host tests use Python source contracts, SQLite and scheduling models, not Kotlin execution. Android instrumentation tests use isolated app-private preferences; the failed-commit @LargeTest must run alone in a fresh instrumentation process and simulates a false result after the cache changes, not a real disk write failure. No Android compile, instrumented test or device run here. Before any diagnostic install: independently review and compile, race actual lifecycle/worker/reconciliation with Stop/Pause/Resume and blocking providers, and inject process death, real disk/Room faults and permission revocation. Prove provider and database safety independently.
