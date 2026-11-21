# Automatic reading shelves

When a library EPUB successfully opens, a NEW or TBR entry becomes READING. A
FINISHED entry stays finished on reopening. Automatic writes update only the
app database shelf field; they do not write into the EPUB. An externally opened
file without a matching library row has no shelf to update.

For completion, the reader checks the **last unique resource in the EPUB's
reading order**, not the last table-of-contents entry, the percentage shown in
the reader, or the estimated positions. Back matter included in the reading
order therefore counts. An EPUB with missing/duplicate last resource paths is
not automatically completed. Material the publisher omitted from the reading
order cannot be inferred.

For reflowable paginated books the Readium 3.1.2 pagination callback must report
the last displayed page of that final resource, respecting LTR/RTL. For normal
vertical scrolling, the final resource's loaded document must be at its actual
scroll bottom. The navigator's current visible resource and layout must still
match after the check, and images/fonts must have loaded. Completion is checked
only once per visit to the end; leaving and returning allows another check. A
manual NEW, TBR, or FINISHED selection cannot be overwritten by the completion
query; a manual READING selection can become FINISHED on a later arrival at the
end. The user may always change the status manually afterward.

For fixed-layout books the navigator's confirmed location must identify the
last reading-order resource. Readium 3.1.2 reports the left resource of a
spread, so a final page on the right may not be detected. The initial fixed
locator is deliberately ignored until the navigator emits a new location: a
single-page book or a book reopened at the end may therefore not automatically
finish. For safety, vertical writing, horizontal scroll, duplicate final paths,
missing visible-element locators, unready/broken images or fonts, or layout
changes can also leave a book on READING; the user can mark it FINISHED manually.
An end reached before content becomes ready may require another navigation to
trigger a check. Unusual or dynamically changing publisher layouts and books
with embedded content require real-device validation. The existing progress
percentage display is approximate and is not used to set FINISHED.

Host-side smoke checks: `python3 tests/test_shelf_guards.py` (requires Node).
Kotlin predicate tests: `gradle :app:testDebugUnitTest` with the Android SDK and
Gradle installed. Device tests should cover LTR and RTL pages, scroll, back
matter, one-page and fixed/spread books, changing manual status, rotation,
resuming at the end, incomplete images and slow layout, and original file
integrity. No test should rely on the percentage indicator.
