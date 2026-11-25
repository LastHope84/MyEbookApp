# MyEbookApp

MyEbookApp is a personal Android ebook reader and library. It is built for one
reader's shelves rather than as a store product: the library lives on the
device, there is no account, and there is no analytics.

Current release: **1.15.0** (Android 8.0 or later, arm64-v8a).

## Features

### Library

- Scan folders and their subfolders through Android's folder picker, with a
  foreground service so scans can continue in the background. Pause, resume or
  stop from the scan banner.
- Add one or more files with **Add File**, or open EPUB, CBZ, CBR, PDF, MOBI or
  AZW3 files from a file manager, Downloads or another app. A visible copy is
  also saved in `Downloads/MyEbookApp`.
- Search an on-device index of title, author, publisher, series and tags.
  Search needs at least two letters.
- Cover grid or detailed list, sorted by title, author or recently opened, with
  an arrow to reverse the order. The menu groups shelves into collapsible
  sections.
- Favorites, To Read / Reading / Finished shelves and custom shelves.
- Built-in shelves: **Comics (CBR/CBZ)**, **PDF** and **Not in a custom
  shelf**.
- Edit metadata and covers locally, or look for matching editions online.
- Clean duplicate library entries (same file name and size), keeping the most
  recently used copy; files are not deleted.
- Back up the library, covers and settings to a `.zip`, optionally including
  the book files the app owns; restore replaces the library after confirmation.
- Activity log, storage summary, temporary-cache cleanup and converted-original
  visibility.

### Reading

- **EPUB** through the Readium Kotlin Toolkit: saved position and progress,
  table of contents, in-book search, rotation, and appearance controls for
  theme, font, size, spacing and margins. The OpenDyslexic, Accessible DfA and
  iA Writer Duospace fonts are included.
- **Read aloud** with the phone's text-to-speech engine, including a media
  notification with Play/Pause/Stop and page following while the voice reads.
- **CBZ and CBR comics**: page swiping, tap zones, pinch and double-tap zoom
  with panning, a page slider, and a remembered right-to-left mode for manga.
  ComicInfo.xml metadata and the first page as cover are used when present.
- **PDF**: page rendering through Android's `PdfRenderer`, plus a selectable
  text layer through PDFBox for copy, dictionary lookup, offline translation,
  read-aloud and whole-document search. Scanned PDFs without a text layer keep
  the page controls and hide the text actions.
- **Offline translation** (arm64 build): install language packs from Settings
  and translate selected text or a whole EPUB. The original book is kept and a
  translated copy is added.
- Dictionary lookup for selected words, using online sources.
- Share a book, or prepare a Send to Kindle email for you to review and send.
- Delete the app's own copy of an imported or converted book; source files in
  your folders are never deleted.

### Conversion

- Convert MOBI, AZW, AZW3 and PRC files to EPUB with libmobi. Conversion
  quality depends on the source; encrypted or DRM-protected files are refused.
- Converted EPUBs go to app storage by default. **Choose folder** in Settings
  picks another folder; **Use app storage** returns to app storage. If the
  chosen folder is unavailable, the EPUB is saved in app storage instead.
- Successfully converted originals are hidden by default, not deleted.

## Supported files

| Format | What the app does |
| --- | --- |
| EPUB | Opens in the reader. |
| CBZ | Opens as a comic. |
| CBR | Opens as a comic; ZIP-based CBR files use the ZIP reader. |
| PDF | Opens as pages, with text features when the PDF has a text layer. |
| MOBI, AZW, AZW3, PRC | Added to the library and offered conversion to EPUB. |

The folder scanner ignores other formats and hidden folders. EPUB, comic and
PDF compatibility varies between files.

## Install

Prebuilt APKs are debug-signed and published in two places:

- Latest on GitHub:
  <https://github.com/LastHope84/MyEbookApp/releases/download/latest/MyEbookApp-latest.apk>
- Latest on the project server:
  <https://rhealyn.clodhost.com/downloads/MyEbookApp-latest.apk>
- GitHub Actions also keeps each build as the `MyEbookApp-debug-apk` artifact
  for 14 days: <https://github.com/LastHope84/MyEbookApp/actions/workflows/build.yml>

Android only accepts an update when the signing certificate matches the
installed app. Uninstalling to switch signatures can delete the library,
progress and app-private files, so make a backup first.

## Build from source

The CI workflow is the reference build. It uses:

- JDK 17
- Gradle 8.13
- Android SDK 35 (36 for the offline-translation build)
- Android NDK 26.1.10909125
- CMake 3.22.1 or newer

The repository does not include libmobi or a Gradle wrapper. Fetch libmobi
v0.12 and build the normal (no offline translation) debug APK with:

```sh
git clone --depth 1 --branch v0.12 https://github.com/bfabiszewski/libmobi.git app/src/main/cpp/libmobi
cp app/src/main/cpp/config.h app/src/main/cpp/libmobi/config.h
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

The published arm64 build also compiles Foxlet Translate 0.5.0 from the pinned
commit `0eff2a15032362115848a496ce1df8ed9fc68650` and passes the resulting AAR
with `-PfoxletAar=/path/to/foxlet-release.aar`. Without that property the app
builds with a fail-closed translation stub (`app/src/nofoxlet`).

Source-level regression tests live in `tests/` and need only Python 3:

```sh
python3 -m unittest discover -s tests
```

## Project layout

```text
app/src/main/java/          Library, readers, conversion, backup and settings
app/src/main/cpp/           JNI wrapper and CMake build for libmobi
app/src/foxlet/             Real offline-translation runtime (arm64 build)
app/src/nofoxlet/           Fail-closed translation stub (normal build)
app/src/main/res/           Icons and Android resources
docs/USER_GUIDE.md          User guide (also available inside the app)
licenses/                   Full third-party licence texts
tests/                      Python source-level regression tests
```

## Privacy

- Library records, reading progress, favorites, shelves and settings are stored
  on the device. The app has no account and sends no telemetry.
- Files added with Add File or opened from another app are copied into app
  storage and also saved in `Downloads/MyEbookApp`, so you can find them outside
  the app.
- Online metadata lookup sends the title and author you enter to catalogue
  services. Dictionary lookups send the selected word to online sources.
- Language packs are downloaded from the model provider only when you choose
  them; translation itself runs on the device.
- Sharing hands the file to the app you choose. Send to Kindle prepares an
  email for you to review and send; the app does not send it.
- A backup file contains library details, settings and your Kindle email
  address. Keep it somewhere safe.

## Limitations

- Some EPUBs may not open correctly, and conversion quality depends on the
  source file.
- PDF text features need a PDF with a real text layer.
- Offline translation is only in the arm64 build and needs downloaded language
  packs.
- Restore replaces the library on the device. Books added after the backup are
  removed from the library, though their files are not deleted.
- Folder access depends on Android's persisted permission. On a new phone,
  re-add the folder and rescan.
- Aggressive battery settings, especially on MIUI/HyperOS, can stop background
  scans. Allow notifications and remove battery restrictions for the app.

## Licence

Copyright (c) 2026 LastHope84.

MyEbookApp's own code is free software: you can redistribute it and/or modify
it under the terms of the **GNU General Public License, version 3 or later**
([LICENSE](LICENSE)). Derivative works must stay under the same licence and
make their source available.

CBR support uses junrar under the UnRAR licence, which is free to use but not
GPL-compatible. An additional permission under GPLv3 section 7 allows the
combination; see [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). A build without
junrar and CBR support would be strictly GPL-only.

Third-party components keep their own licences, including libmobi
(LGPL-3.0-or-later), Readium (BSD-3-Clause), Apache PDFBox (Apache-2.0),
Foxlet Translate (MIT), Mozilla translation models (MPL-2.0), and the bundled
fonts (SIL OFL 1.1). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
the [`licenses/`](licenses/) directory for details.

## Credits

MyEbookApp is a personal project by LastHope84, built with Claude (Anthropic).

Thanks to the libmobi, Readium, PDFBox, junrar, Foxlet, Bergamot, Marian and
Mozilla translations projects, and to the font authors.
