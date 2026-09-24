# Progress — EpubReader

Update this page at meaningful work checkpoints. Keep the active milestone, recent verified progress, blockers, and the next few actions concise.

## Current status

- **Stage:** Initial Android implementation.
- **Active branch:** `feature/android-saf-library`.
- **Current milestone:** Implement narration and reliable reading-position restoration on top of the first library/reader experience.
- **Device strategy:** Host AVD is the default for UI, SAF, lifecycle, and Android System TTS. Pixel 10 is reserved for real Pocket TTS/LiteRT inference and performance.

## Completed

- [x] Researched eVoice Reader features and recorded source links/technology snapshot.
- [x] Created product design, technical design, contributor guidance, and project README.
- [x] Chose Android 16+ minimum, SAF folder-based book access without copying, and no scanned-PDF OCR in MVP.
- [x] Specified a traditional inset-safe UI that handles Android 16 enforced edge-to-edge behavior and camera cutouts.
- [x] Set AVD-first development/testing, Android System TTS in AVD, and Pixel 10-only physical Pocket inference checks.
- [x] Specified host-to-Pixel model provisioning via ADB and caching verified model weights on-device.
- [x] Added long-term TODO, progress tracking, and learnings-log requirements/files.
- [x] Created a runnable single-module Compose app with Kotlin 2.4.20, AGP 9.4.0, Gradle 9.7.1, API 36 min/target, and API 37 compile SDK required by latest stable Compose libraries.
- [x] Added Room-backed folder/book records using content URIs, SAF folder and single-document pickers, recursive EPUB/PDF discovery, manual rescanning, remove-from-library actions, and scan error states.
- [x] Added an initial Material bookshelf UI with inset-aware Scaffold defaults and a focused `BookFormat` unit test.
- [x] Confirmed `:app:assembleDebug` succeeds against the installed host SDK.
- [x] Added Readium Kotlin Toolkit 3.3.0 parsing and EPUB navigator integration. A generated minimal EPUB opened directly from a persisted SAF folder URI on the API 37 Pixel_10 AVD; the page rendered and the reader returned no runtime exception.
- [x] Added core-library desugaring required by Readium 3.3.0 and pinned AndroidX Fragment 1.9.1 for the navigator host.
- [x] Added library navigation for Recent files, All files, Folders, folder contents, and Settings; folder management presents recent and all registered folders.
- [x] Added System/Light/Dark theme selection, EPUB font family/scale preferences, Android System TTS engine/rate settings, and a link to Android's voice/engine settings. Pocket TTS remains unavailable until its provider is integrated.
- [x] Added lazy Readium metadata/author and embedded-cover extraction into app-owned thumbnails, with dates and reading-progress percentages in book cards.
- [x] Added Readium PDFium 3.3.0 rendering. Verified both supplied EPUBs and the searchable four-page PDF on the API 37 AVD; PDF page 2 reopened after saving 50% progress.
- [x] Added the user-provided EPUB/PDF fixtures under `tests/` with embedded-source/licensing notes in `tests/README.md`.
- [x] Verified dark appearance survives app relaunch and added the Room v1→v2→v3→v4 migrations for open date, book metadata, cover/progress, and metadata-load state.

## Immediate next steps

1. Repeat the library, appearance, EPUB, and PDF smoke tests on a stable API 36 AVD and test the window-inset matrix.
2. Replace percentage-only restoration with persisted Readium locator JSON and validate process recreation.
3. Add Android System TTS narration through Readium's TTS path; connect the stored engine/rate settings and verify AVD voice/cancellation behavior.
4. Audit PdfiumAndroid/JitPack licensing and native ABI/16 KB page-size support; validate more representative PDFs before claiming broader support.
5. Pin Pocket TTS and create a Pixel 10 model provisioning/inference spike.

## Blockers / pending verification

- Readium PDF extraction/TTS locator mapping must be proven with representative PDFs.
- Readium 3.3.0 is pinned and its basic EPUB/PDF rendering is verified on API 37. The Pocket TTS revision and model artifact/release URL still need to be pinned and verified.
- SAF folder accessibility depends on Android's picker and the document provider; the basic local-folder grant and scan flow worked on the API 37 AVD. Repeat on API 36 and document provider constraints.
- Readium 3.3.0 opened the supplied EPUB fixtures directly through persisted `content://` URIs on the API 37 AVD. Percentage-based progress restores the PDF page position; serialized Readium locator persistence and reader process restoration remain unimplemented.
- The supplied searchable PDF renders and resumes at the saved percentage with Readium's PDFium adapter. No PDF OCR, TTS text extraction, or synchronized highlighting has been validated.
- AGP 9.4 currently requires opting out of its new DSL to use Kotlin 2.4.20's external Android plugin. The opt-out is deprecated and must be revisited when AGP/Kotlin plugin compatibility improves.
