# MyEbookApp user guide

For the current app version.

## Getting started

### Add a folder
Open the library menu (the three lines), tap Add Folder, choose a folder in Android's file picker and grant access. The app scans its subfolders, excluding hidden folders. Scanning can take time; a progress banner appears in the library, and a notification keeps the scan running while the app is in the background. You can pause, resume or stop from the banner. An unreadable book is kept with basic details instead of blocking the rest of the scan.

### Supported files
The folder scanner finds EPUB, MOBI, AZW, AZW3 and PRC files. EPUBs can be read in the app; the other listed formats appear in To Convert. Other formats are not added by this scanner.

### Add more or rescan
Use Add Folder again for another folder. After adding files to a saved folder, open the menu and tap Rescan Folders. The app stores access to selected folders; if a folder is moved or access expires, choose it again.

### Manage folders
Open the menu and tap Manage folders to see saved folders, add more, remove a folder, or open its subfolders. Adding a parent folder includes all its subfolders. Removing a folder removes its library entries, not the ebook files; reading progress and edits for those entries are lost. Removing a subfolder also skips it in future scans so its books do not come back; tap Include again on the same screen, then Rescan Folders, to restore them.

### Search the library
Type in Search in the menu, then choose a shelf. Search applies to that shelf and matches indexed title, author, publisher, series and tags. Clear the search to see the full shelf. Tap a cover for its details and actions.

## Shelves and book details

### Choose shelves
Tap a book, then tap the heart on its details page to add it to Favorites or remove it. Tap the heart on a cover in the library to add or remove a favorite without opening the book; it fills red when the book is a favorite and is hidden while you are selecting books. Under Reading shelf, choose Unclassified, To Read, Reading or Finished. Select the matching shelf from the library menu. Favorites is independent of the one reading status.

### Reading status
You can change the reading shelf yourself without changing saved progress. When an EPUB opens successfully, an Unclassified or To Read book moves to Reading; a Finished book stays Finished when reopened. The reader attempts to mark a Reading book Finished at the end of its final reading-order section, including back matter. End detection is conservative and may miss some layouts, so change the status manually if needed.

### Custom shelves
In the library menu, tap + Create shelf and give it a name. Open a book and tap Custom shelves to add or remove it; the button appears once at least one shelf exists. A book can belong to multiple custom shelves as well as a reading shelf. Delete beside a custom shelf removes the shelf, not its books or reading progress.

### All Books, Recent and To Convert
All Books shows visible library entries. Recent shows up to 50 most recently added visible entries, including newly converted EPUBs, in library insertion order; older imports follow their original scan order. To Convert lists visible non-EPUB entries. Counts in the menu exclude hidden originals and are independent of search.

### Details and path
Book details show available metadata and a Path. Tap the path to try opening its folder in a file manager. If that is not possible, the app copies a readable path instead.

### Remove from Library
In book details, tap Remove from Library. This removes the library entry, not the source file, and does not ask for confirmation. Its app-only edits, favorite, status and library progress entry are removed too. A rescan can add the file again; keep a separate backup of anything important.

### Selection
Long-press a cover to enter selection mode; tap covers to select or deselect them. All selects every book on the current shelf matching the active search, including books not yet loaded on screen. Actions lets you move books to a reading shelf, manage custom shelves, change Favorites, share files, or convert non-EPUB books. If none of the selected books are favorites it offers Add to favorites, if all are it offers Remove from favorites, and with a mix it offers both. Favorite changes apply to every selected book after confirmation. In Manage custom shelves, Remove is available for the currently open custom shelf. Press Android Back or tap × to leave selection mode.

## Reading an EPUB

### Open and continue
Tap a book, then Read Book or Continue. The reader stores your position and progress for a library book; Continue returns to the saved position when available. EPUB layout and navigation depend on the book.

### Reader controls
Tap the reading area to show or hide the controls. They also hide automatically after a few seconds. The top bar has Back, Find, TOC, Rotate and Aa. Rotate switches quickly between portrait and landscape. The bottom bar has previous/next arrows, a position slider, page/position and percentage. Drag the slider to jump when the book supplies positions.

### Chapters and search
Tap TOC and choose an entry to jump there. If the book has no table of contents, the app offers reading-order sections instead. Tap Find, enter a word or name, tap Find and select a result. Results depend on the book's searchable text.

### Dictionary
Select a word in the book and choose Define in the selection menu. The app looks it up online, preferring Italian or English according to the book language. Search / Translate in Browser opens Wiktionary. Availability and definitions depend on the online sources.

## Reading appearance

### Open Aa
While reading, tap the page to show controls, then tap Aa. Swipe the sheet to see all options. Changes are saved as reader preferences and apply to the reader, not the library theme.

### Page and type
Choose Light, Sepia, Dark, AMOLED or E-Ink. Choose a typeface, including Dyslexic and Accessible, then adjust font size, line spacing and margins. Horizontal rows can be scrolled to reveal more choices; results may vary with the EPUB.

### Screen
Set orientation to Auto, Portrait or Landscape, or use the Rotate button in the reader top bar for a quick switch. Adjust brightness or use Reset to Auto to return to system brightness. Adjust the warm filter. Fullscreen hides system bars while reading; swipe from the screen edge to temporarily reveal them.

### Behaviour
Switch volume-key page turning, vertical scroll, keeping the screen awake and the mini footer on or off. With volume-key turning enabled, volume down goes forward and volume up goes back. The footer shows chapter and progress when controls are hidden.

## Edit book information

### Edit locally
Tap a book, then Edit. If an EPUB is still being scanned, wait for its metadata scan to finish. Edit title, author, series and book number, tags (separate with semicolons), date, publisher and language. Use Change Cover to choose an image, or Remove to remove the app's cover. Tap Save or Cancel.

### Search online
In Edit, enter a title or author and tap Auto-Fetch Metadata & Cover. The title and author are sent to online catalogues. Choose a match, review its edition and select which available fields to apply. Apply to draft, then tap Save. A selected cover must finish downloading first. Unchecked fields stay unchanged.

### Original file
Edits and cover changes are saved in this app's library. The original ebook is not rewritten. When you share an edited EPUB or send it to Kindle, the app prepares a copy that includes the corrected title, author, series, tags, publisher, date, language and cover.

## Convert to EPUB

### Conversion
Choose To Convert in the menu, tap a non-EPUB book and tap Convert to EPUB. The app attempts to create a separate EPUB, adds it to the library and opens its details. Once a MOBI or AZW3 is successfully converted and linked, its original library entry is hidden by default, not deleted. Conversion can fail or produce imperfect results depending on the source; check the output. Failed conversions remain visible and the source file is kept.

### Show or hide originals
Open the library menu and tap Converted original visibility. Switch on Show converted originals to reveal linked originals on shelves and include them in counts; switch it off to hide them again. Previously converted books cannot be linked automatically, so their originals remain visible. Hiding an entry never deletes its file.

### Where it goes
By default, converted EPUBs are in the app's private storage. Share the converted book to save a copy elsewhere. App storage can be lost if the app is uninstalled or its data is cleared. Although Settings displays Pick SD / Folder and Reset, those controls are not connected in this version; do not rely on them to change the destination.

## Share and Send to Kindle

### Share
Tap a book, then Share, and choose an app from Android's share sheet. The app prepares the book file as an attachment; you finish the action in the chosen app. If you edited the book's details or cover in the app, the attachment is a copy that includes those corrections; the original file is untouched.

### Kindle
In the library menu, open Settings, enter your Send to Kindle email and tap Save. Open an EPUB's details and tap Send to Kindle; choose an email app in the chooser, check the prepared message and send it yourself. A non-EPUB must first be converted for this button. Delivery depends on your mail app and Amazon's current requirements, including approved sender settings.

## Library settings

### Appearance
Open the library menu and tap Settings. Choose the app theme mode and accent; the accent row scrolls sideways. AMOLED Pitch-Black applies to dark app UI. Tap Done to close Settings.

### Other settings
Save your Kindle email here. The Reopen Last Book on Launch switch is displayed, but automatic reopening is not connected in this version. The output-folder picker and Clean Duplicates buttons are also not connected. Do not rely on these controls to perform those actions.

### Storage
The storage summary shows app covers, converted EPUBs and temporary cache. Clear Temp Cache removes temporary working, share and reader copies, not the original source ebooks or the app's converted EPUB folder. Avoid clearing it while an attachment is still being used by another app.

## Storage, safety and troubleshooting

### What is stored
The app keeps library records, metadata edits, favorites, reading statuses, progress and preferences on this device. Folder books remain in their original locations; access depends on Android's folder permission. Back up your original files separately. The app has no visible export for its library edits or shelves.

### Uninstall or clear app data
Clearing app data or uninstalling can remove library records, preferences, app-only metadata and EPUBs in private app storage. Copy important converted EPUBs out using Share before doing so. Original files in separately chosen folders are not removed by the library's Remove action.

### Missing or inaccessible books
Check that the file is in a selected folder and still accessible, then use Rescan Folders or add the folder again. If Android reports that folder access was lost, add the folder again and allow access. Clear any active library search and check All Books. Scans may take time.

### Scan details
If a scan stops, open the scan banner and tap Scan details. The log shows which folder failed and why; include it when asking for help. Background scans may also be stopped by aggressive phone battery settings, so allow notifications and remove battery restrictions for the app.

### Book or conversion fails
Check that the source file is accessible and intact. The reader opens EPUBs; use Convert for supported non-EPUB entries. Not every source can be converted or read successfully. Keep the source and try another copy or app if needed.

### Online search or dictionary fails
Check your connection and try again. For metadata, try a shorter title or author and check the edition before saving. No results can also mean the catalogues are unavailable. For dictionary lookup, try a single word or the browser option.

### Kindle or sharing fails
Check that the file is still accessible and a suitable sharing or email app is installed. For Kindle, save the correct Kindle address, check the attachment and recipient in your mail app, send the message, and review Amazon's sender and delivery requirements.
