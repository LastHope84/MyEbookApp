# CI source composition, 22 November 2026

**Historical porting notes:** the 27-file/13-part figures below describe the older CI experiment, not this build-preparation copy. See `diagnostic-buildprep-20261122.md` for current figures and safeguards.

The preview's MainActivity is now an integrated source containing whole-book
translation and the folder/scanner UI. Both encoded payloads contain that exact
file. CI uses `tests/restore_preview_sources.py` to verify both inputs, reject
conflicting overlaps before writing, and restore them; it then runs both CI
integration test files before Gradle. Folder removal and restore remain disabled
in the read-only adapter. These are static source checks, not a build or device
verification.

The folder payload now contains only app and docs files. It cannot overwrite
the restore program, tests, or translation inputs. It contains 27 files in 13
numbered parts; decoded JSON SHA-256 is
`0d478a096749c6a45cacb4a26112b1dfae276e69ed66dab0f5dd2e82c10d4b4e`.

## Porting to the implementation copy

First reconcile its current integrated sources; do not replace newer scanner or
translation code with this experimental snapshot. Port the workflow, restore
script, CI tests and updated translation refresh script. Rebuild *both* payloads
from the final merged sources (including every intended folder payload file),
rather than blindly copying this snapshot's payloads. Update the folder digest,
part count and file count constants in `tests/restore_preview_sources.py`; remove
obsolete part files. Ensure the translation MainActivity and folder MainActivity
are identical. The folder payload paths must stay under app/ or docs/, never
include tests or translation inputs. Do not remove the conflict check to make a
mismatch pass.

For MainActivity, `python3 tests/refresh_ci_main.py` refreshes the translation
payload from integrated source; the folder payload must also be regenerated.
Run from the implementation root:

```
python3 tests/restore_preview_sources.py
python3 -m unittest discover -s tests -p 'test_final_preview_integration.py' -v
python3 -m unittest discover -s tests -p 'test_ci_input_consistency.py' -v
python3 -m unittest discover -s tests -p 'test_*.py'
```

The restore command writes payload contents into that checkout. Before running it,
compare/rebuild payloads to avoid replacing newer source. Separately run an actual
CI/Gradle APK build and Android/device/provider tests; none are demonstrated by
these Python tests.
