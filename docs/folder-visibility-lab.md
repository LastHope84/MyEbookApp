# Folder visibility lab — isolated, not an app feature

This checkout copies the integration preview. Folder removal is still disabled. The
`tests/folder_hide_lab.sql` schema and its Python tests are a **disposable SQLite
model**, not a Room migration, not part of the Android build, and not authority to
change a real library. No production queries, UI, scanner, permissions or document
operations are wired to it. No source book or user data is deleted or moved.

The model explores a reversible visibility view over intact book rows. It requires
exact root-scoped exclusions, at least one observed route, every route excluded,
verified identity and scanner origin, no independent/unknown owner and no uncertain
dedup, and a fully enumerated root inventory. Missing facts retain the row. The
model invalidates its mock lease on every local routing, root, identity or exclusion
mutation. Data including notes and favorites remain in the book row. Clearing an
exclusion makes it visible again; a rollback preserves the prior state.

## Fundamental limitation: do not deploy

The lease is a stand-in for a guarantee that **does not exist in general SAF**. A
provider can change a route or revoke access without any local SQL mutation, making
an apparently complete snapshot stale. Two matching traversals cannot eliminate the
race. In addition, scanner origin is not proof that a manual import, name/size dedup,
another root, or an unobserved alternate parent does not own a book. The test suite
contains a deliberately failing-to-detect external change counterexample. It sets the
mock lease to valid solely to exercise the hypothetical calculation. The model has no
secure way to obtain or maintain that lease and MUST NOT be integrated as-is.

The stored mock lease also survives a process restart; a real design would need a
fail-open startup invalidation, coordination against in-flight scan and UI transactions,
a coherent durable root configuration, a usable alternate URI without releasing
any grant, complete query coverage (including search, shelves and paging), and recovery
from interrupted operations. Even these measures do not solve provider-side races.
Database schema changes need a new version/migration and migration tests: the copied
v9 has a known schema/index compatibility problem. Implementing a Boolean validity
flag alone would conceal stale ownership and is not acceptable.

Run with `python -m unittest discover -s tests -p test_folder_hide_lab.py -v`.
These tests use CPython's SQLite, not Android Room, a real SAF provider, or a device.
