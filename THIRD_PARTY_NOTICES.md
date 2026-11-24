# Third-party notices

MyEbookApp is built with the third-party components listed below. The full
licence texts are in the [`licenses/`](licenses/) directory. Versions are the
ones used by the current build.

## Runtime components

### libmobi — MOBI/AZW3 conversion

- Project: https://github.com/bfabiszewski/libmobi (v0.12)
- Licence: GNU Lesser General Public License v3.0 or later
  ([licenses/LGPL-3.0.txt](licenses/LGPL-3.0.txt))
- Copyright (c) 2014-2022 Bartek Fabiszewski

libmobi is compiled into a separate shared library (`libmobiconv.so`) together
with the small JNI wrapper in `app/src/main/cpp/mobi_jni.c`, so the library can
be replaced. The upstream source is available at the project URL above; the
build fetches the v0.12 tag and copies `app/src/main/cpp/config.h` into it.
libmobi itself is unmodified. Encrypted or DRM-protected files are refused.

### junrar — CBR/RAR decompression

- Licence: UnRAR ([licenses/UnRAR.txt](licenses/UnRAR.txt))
- junrar is a Java port of the UnRAR decompressor by Alexander Roshal.

MyEbookApp only reads RAR archives. It does not create RAR archives and does
not include a RAR compressor. CBR files that are really ZIP archives are read
with the platform ZIP support instead. Because the UnRAR licence is not
GPL-compatible, junrar is combined with this GPL program under the additional
permission in [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md).

### Apache PDFBox / pdfbox-android — PDF text layer

- Licence: Apache License 2.0
  ([licenses/Apache-2.0.txt](licenses/Apache-2.0.txt))
- Copyright the Apache Software Foundation.

Used for PDF word positions, text selection, search and read-aloud. PDF page
rendering uses Android's built-in `PdfRenderer`.

### Readium Kotlin Toolkit — EPUB reading

- Project: https://github.com/readium/kotlin-toolkit (3.1.2)
- Licence: BSD 3-Clause
  ([licenses/BSD-3-Clause.txt](licenses/BSD-3-Clause.txt))
- Copyright (c) 2017, Readium. All rights reserved.

Readium also bundles Readium CSS and three fonts used by the reader's
appearance settings:

| Font | Licence | Copyright |
| --- | --- | --- |
| OpenDyslexic | SIL Open Font License 1.1 ([licenses/OFL-1.1.txt](licenses/OFL-1.1.txt)) | Abbie Gonzalez |
| Accessible DfA | SIL Open Font License 1.1 | Copyright (c) Orange 2015; Reserved Font Name Accessible-Dfa |
| iA Writer Duospace (IBM Plex) | SIL Open Font License 1.1 | Copyright © 2017 IBM Corp.; Reserved Font Name "Plex" |

### AndroidX, Jetpack Compose, Material Components

AndroidX libraries (Core, Activity, Fragment, Lifecycle, Media, Room, Paging),
Jetpack Compose and Material Components are licensed under the Apache License
2.0.

### Coil, OkHttp, Okio

Coil (image loading) and its OkHttp/Okio dependencies are licensed under the
Apache License 2.0.

### Kotlin standard library and kotlinx.coroutines

Licensed under the Apache License 2.0.

### Foxlet Translate — offline translation engine

- Project: https://github.com/yinvoke/foxlet-translate (v0.5.0, commit
  `0eff2a15032362115848a496ce1df8ed9fc68650`)
- Licence: MIT ([licenses/MIT.txt](licenses/MIT.txt))

Foxlet includes the Bergamot translator (MPL 2.0), Marian (MIT), SentencePiece
and ruy (Apache 2.0), and other components. The Foxlet AAR bundles the complete
third-party licence materials and its NOTICE under
`io/github/yinvoker/foxlet/licenses/`; those files are included in the APK.

Mozilla Firefox Translations models are downloaded only when a language pack
is installed by the user. They are licensed under MPL 2.0
([licenses/MPL-2.0.txt](licenses/MPL-2.0.txt)) and are not bundled in the APK.

## Build and test only (not distributed in the APK)

- JUnit 4.13.2 — Eclipse Public License 1.0.
- Gradle, the Android Gradle Plugin and the Kotlin compiler — Apache License
  2.0.
- Python 3 and its standard library are used for the source-level regression
  tests; no third-party Python packages are required.

## Notes

- MyEbookApp's own code is licensed under the GNU General Public License,
  version 3 or later (see [LICENSE](LICENSE)). The junrar exception is in
  [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md).
- No component is used to bypass DRM. Encrypted MOBI/AZW3 files are refused,
  and password-protected CBR files are refused.
- If you redistribute this app or a modified version, keep these notices and
  the licence texts, make the corresponding source available under the GPL,
  and make the libmobi source available as required by the LGPL.
