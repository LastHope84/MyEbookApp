# MyEbookApp

A personal Android ebook reader and library, built to help you organise your books and make reading comfortable. The app is under active development, so some features and controls are still a work in progress.

## What it can do

- Scan selected folders and their subfolders for books and search the library.
- Read EPUBs, save your place, search within a book and navigate its contents.
- Adjust the reader's theme, font, spacing, brightness and other reading preferences.
- Keep favourites and manually assign books to **To Read**, **Reading** or **Finished**.
- Edit library metadata and covers locally, or look for matching editions online.
- Attempt to convert MOBI-family files to EPUB, then share books or prepare an email to Send to Kindle.
- Open the built-in user guide from the app's library menu.

### Supported files

| Format | What the app does |
| --- | --- |
| EPUB | Opens in the reader. |
| MOBI, AZW, AZW3, PRC | Finds them in scanned folders and offers conversion to EPUB. Conversion is not guaranteed; encrypted or DRM-protected MOBI files cannot be converted. |

Other formats are not added by the folder scanner. EPUB compatibility can also vary between books.

## Getting started

1. Install a compatible APK on an Android device running **Android 7.0 or later**. The current build targets ARM64 and ARMv7 devices.
2. Open the app, tap the library menu and choose **Add Folder**.
3. Select a folder containing ebooks and grant access. The app scans its subfolders too.
4. Tap an EPUB in the library and choose **Read Book**. Use **To Convert** for compatible non-EPUB books.

For more detail, see the [full user guide](docs/USER_GUIDE.md), also available inside the app.

## APKs and installation

This repository builds a **debug APK** with [GitHub Actions](https://github.com/LastHope84/MyEbookApp/actions/workflows/build.yml). Open a successful run and look for its `MyEbookApp-debug-apk` artifact. Artifacts are currently kept for 14 days and downloading them may require a GitHub account. They are build artifacts, not a promised stable release channel.

Android accepts an update only when its signing certificate matches the installed app and its version code is suitable. A locally built APK may use a different signature from a CI-built APK. Uninstalling to switch signatures can delete the app's library data and privately stored conversions, so export important converted books first.

## Your files and privacy

The current library edit flow stores metadata and cover changes in the app; it does not rewrite the source ebook. Sharing sends the file itself, not those app-only edits. Removing a book from the library removes its library entry, not the original file. Conversion creates a separate EPUB; check the result and keep the source.

Library records, reading progress and preferences are stored on the device. Android backup may apply. Converted EPUBs are saved to app-private storage by default and may be lost if you uninstall or clear app data; use Share to save a copy elsewhere and keep separate backups of originals.

Online metadata lookup sends the title and author you enter to catalogue services. Dictionary lookups send selected words to online sources. Sharing hands a file to the app you choose; Send to Kindle prepares an email for you to review and send, rather than sending it automatically.

## Current limitations

- Reading statuses are **manual**: opening a book or reaching its end does not automatically change its shelf. Custom shelves are not available in this version.
- Some displayed settings are not connected yet, including the conversion-output folder picker, duplicate cleanup and reopening the last book on launch.
- Folder scanning runs in a foreground service so it can continue while the app is in the background. Hidden folders are skipped. An unreadable EPUB is kept in the library with basic details instead of blocking the rest of the scan.
- Some EPUBs may not open correctly, and conversion quality depends on the source.
- The app has no visible export for its library edits or shelves. See the [guide](docs/USER_GUIDE.md#storage-safety-and-troubleshooting) before clearing app data or uninstalling.

## Build from source

The project uses Kotlin, Jetpack Compose, Room, Readium and a native libmobi component. The CI configuration is the reference for the current build environment.

Requirements include JDK 17, Android SDK 35, Android NDK 26.1.10909125, CMake 3.22.1 or newer, Git and Gradle 8.13. The repository does **not** currently include a Gradle wrapper or a bundled copy of libmobi.

From the repository root, matching the CI setup:

```sh
git clone --depth 1 --branch v0.12 https://github.com/bfabiszewski/libmobi.git app/src/main/cpp/libmobi
cp app/src/main/cpp/config.h app/src/main/cpp/libmobi/config.h
gradle assembleDebug
```

The APK is generated under `app/build/outputs/apk/debug/`. This is a debug build; see the signing warning above before installing it over an existing app.

## Licence

There is currently no project licence file in this repository. Please do not assume that the project code is licensed for reuse or redistribution. Third-party components have their own licences.
