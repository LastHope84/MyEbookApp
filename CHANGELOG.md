# Changelog

Notable changes to MyEbookApp, newest first. Versions follow `versionName` in
`app/build.gradle.kts`; stable releases are also tagged `vX.Y.Z` on GitHub.

## [1.18.5]
### Changed
- The reader controls stay on screen for 6 seconds instead of 4; using them restarts the timer.

## [1.18.4]
### Fixed
- Tapping the reader's progress slider no longer snaps the thumb back to the old position; the EPUB and PDF bars hold the chosen spot until the reader confirms it.

## [1.18.3]
### Added
- A book on the Finished shelf is shown as 100% read in the grid, list and details; its saved position is untouched.

## [1.18.2]
### Fixed
- The A–Z rail now hides and returns instantly when selection starts or ends (the index is cached and the grid no longer re-lays-out).

## [1.18.1]
### Added
- Remove from library in the selection Actions menu, with a confirmation and the same undo as the single-book removal.

## [1.18.0]
### Added
- Removed books stay removed: folder scans skip them, and Settings → Removed books restores one or all.
- A ❝ button in both readers opens the book's saved quotes without leaving it.
### Changed
- Clean Duplicates now remembers its removals too.
- Database version 10 (`removed_books`).

## [1.17.0]
### Added
- Saved quotes: select a passage in an EPUB or PDF and keep it with its chapter or page and date.
- Book details gains a Quotes sheet with copy, share, reopen and delete; quotes are included in backups.
- Database version 9 (`quotes`).

## [1.16.0]
### Added
- A–Z index rail for shelves with 100 or more books in Title or Author order: tap or drag a letter, a bubble follows your finger with the letter and count, `#` for numbers and symbols.
- TalkBack users get jump actions on a single index node.
- The JVM unit tests now run in CI before the APK build.

## [1.15.5]
### Added
- The select-all icon toggles back to the previous selection when tapped again.

## [1.15.4]
### Added
- Mark as read in book details (sets Finished without moving your position).
### Changed
- The bulk Convert action appears only when the selection includes MOBI/AZW3 books.
- The selection bar's All button became a checklist icon with a spinner.

## [1.15.3]
### Changed
- The bulk action "Move to reading shelf" is now "Change reading status".

## [1.15.2]
### Changed
- The bulk action "Manage custom shelves" is now "Move to custom shelf".

## [1.15.1]
### Fixed
- A file sent from another app is imported once per launch; rotation no longer re-imports it.
- Reopen Last Book works after a previous open-with session.

## [1.15.0]
### Added
- Sort direction: an arrow beside the sort label flips A–Z / Z–A and newest / oldest.
- Collapsible drawer sections for reading status, formats and custom shelves.

## [1.14.1]
### Changed
- The built-in "Documents (PDF)" shelf is now "PDF".

## [1.14.0]
### Added
- Files added with Add File or open-with are also saved as a visible copy in `Downloads/MyEbookApp`.

## [1.13.7]
### Added
- Author credit in the credits: "built with Claude (Anthropic)".

## [1.13.6]
### Added
- In-app Credits from Settings, naming every third-party component and font.

## [1.13.5]
### Fixed
- The Recent drawer badge counts the books the Recent shelf actually shows.

## [1.13.4]
### Added
- The app's own code is licensed under GPL-3.0-or-later, with a section-7 permission for junrar/UnRAR.
- CI publishes every main build to a rolling `latest` release and tags create versioned releases.

## [1.13.3]
### Changed
- README rewritten; third-party notices and full licence texts added; in-app guide and docs expanded.
- Python regression tests repaired.

## [1.13.2]
### Changed
- Activity log and Converted original visibility moved from the drawer into Settings.

## [1.13.1]
### Changed
- The format badge sits bottom-left on covers; every cover shows its reading percent.

## [1.13.0]
### Added
- Built-in shelves: Comics (CBZ/CBR), PDF and Not in a custom shelf.

## [1.12.3]
### Changed
- Search debounces typing, needs at least 2 letters, and the FTS index is optimised on open and after scans.

## [1.12.2]
### Fixed
- Settings no longer loads the language-pack catalogue on open.

## [1.12.1]
### Changed
- System Back leaves a custom shelf for All Books; Settings' Done only closes the sheet.

## [1.12.0]
### Added
- Backup and restore to a `.zip` from Settings, optionally including the app's book files.

## [1.11.1]
### Fixed
- The Reopen Last Book switch moves immediately when tapped.

## [1.11.0]
### Added
- Reopen Last Book on Launch, a converted-EPUB folder picker, and Clean Duplicates are now wired.

## [1.10.1]
### Fixed
- PDF selection highlights align with the text baseline.

## [1.10.0]
### Added
- CBR comics through junrar (ZIP-based CBR uses the CBZ path).
- Delete file from device for the app's own copies; a Licences guide topic.

## [1.9.0]
### Added
- PDF text layer: word selection with Copy, Define, Translate and Speak; whole-PDF search; page read-aloud.

## [1.8.0]
### Added
- PDF reading, open-with from other apps, and Add File.

## [1.7.0]
### Added
- CBZ comics with ComicInfo.xml metadata, first-page covers and an LTR/RTL reader.

## [1.6.5]
### Fixed
- The Aa preview uses the real bundled fonts.

## [1.6.4]
### Added
- Live appearance preview in the Aa sheet.

## [1.6.3]
### Fixed
- AMOLED mode is truly pitch black; shelf dialogs separate Done with a divider.

## [1.6.1]
### Added
- A remembered grid/list toggle.

## [1.5.3]
### Changed
- Done is right-aligned in both shelf dialogs.

## [1.5.2]
### Changed
- The Create shelf drawer entry aligns with the others.

## [1.5.1]
### Fixed
- A single cream-on-charcoal cat icon replaces the runtime icon switcher, which crashed on MIUI.

## [1.5.0]
### Added
- Four selectable icon colourways (withdrawn in 1.5.1).

## [1.4.3]
### Changed
- Newly converted EPUBs appear in Recent at their conversion time.

## [1.4.2]
### Changed
- Recent shows recently opened books, newest first, including finished ones.

## [1.4.1]
### Changed
- The third sort option is now Recently opened.

## [1.4.0]
### Added
- Shelf sorting, opt-in auto-scan once a day on open, and a finished card at the end of a book.

## [1.3.1]
### Changed
- Reader top bar uses icons for Find, TOC and Rotate.

## [1.3.0]
### Fixed
- Page sync for read-aloud: a small script reports the text at the page's bottom edge, so pages turn when the voice reaches that line.

## [1.2.9]
### Changed
- Page-follow aims about 250 characters behind the voice and updates every 1%.

## [1.2.8]
### Fixed
- Read-aloud no longer repeats a chapter title; the spoken sentence is highlighted.

## [1.2.7]
### Fixed
- Page-follow reports the end of a finished sentence, so pages no longer turn early.

## [1.2.6]
### Fixed
- Audio focus is requested once; denial no longer blocks speech.

## [1.2.5]
### Fixed
- Page-follow locators no longer carry a guessed book-wide percentage.

## [1.2.4]
### Added
- Activity log is always available from the library menu.

## [1.2.3]
### Fixed
- Read-aloud ignores duplicate speaks and never reloads a chapter that is already playing.

## [1.2.2]
### Fixed
- Read-aloud repeats, queueing and text-position navigation.

## [1.2.1]
### Added
- A media session and notification with Play/Pause/Stop for read-aloud.

## [1.2.0]
### Added
- Read aloud with the phone's TTS engine: play/pause in the reader, chapter advance and a voice picker in Settings.

## [1.1.9]
### Added
- The heart on a library cover toggles the favourite directly.

## [1.1.8]
### Changed
- Custom shelves buttons stay hidden until at least one shelf exists.

## [1.1.7]
### Changed
- The favourite action shows only the fitting option: Add, Remove, or both for a mixed selection.

## [1.1.6]
### Changed
- Separate Add to favorites and Remove from favorites actions.

## [1.1.5]
### Added
- A small heart marker on favourited covers.

## [1.1.4]
### Changed
- The favourite heart moved onto the cover's top-right corner.

## [1.1.3]
### Changed
- The favourite button on book details became a heart icon.

## [1.1.2]
### Changed
- Online cover fetch asks for larger images; library thumbnails are 420px.

## [1.1.1]
### Changed
- Exported files are named "Title - Author"; only a cover you replaced is rewritten.

## [1.1.0]
### Added
- Sharing or sending an edited EPUB exports a copy with the corrected metadata and cover; the original stays untouched.

## [1.0.2]
### Fixed
- Removed subfolders are remembered and skipped by later scans; Include again undoes it.

## [1.0.1]
### Fixed
- Rescans backfill folder provenance for books added before the field existed.

## [1.0] — 2026-11-23
### Added
- First release: folder library with search, favourites, reading shelves and custom shelves; EPUB reader with themes and read-aloud; MOBI/AZW3 to EPUB conversion; metadata editing and covers.
