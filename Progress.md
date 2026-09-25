# Progress — EpubReader

Update this page at meaningful work checkpoints. Keep the active milestone, recent verified progress, blockers, and the next few actions concise.

## Current status

- **Stage:** Initial Android implementation.
- **Active branch:** `main` (reader scroll/voice/double-tap/auto-follow checkpoint merged from `feature/reader-scroll-and-voices`).
- **Current milestone:** Polish the EPUB reading and narration experience: continuous vertical scrolling that fills the window, dynamic Pocket voice selection and text size, double-tap-to-read, narration auto-follow, and background notification controls.
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
- [x] Added System/Light/Dark theme selection, EPUB font family/scale preferences, speech-engine/rate settings, and a link to Android's voice/engine settings. Pocket TTS engine and voice selection were added in the current milestone; device verification is tracked below.
- [x] Added lazy Readium metadata/author and embedded-cover extraction into app-owned thumbnails, with dates and reading-progress percentages in book cards.
- [x] Added Readium PDFium 3.3.0 rendering. Verified both supplied EPUBs and the searchable four-page PDF on the API 37 AVD; PDF page 2 reopened after saving 50% progress.
- [x] Added the user-provided EPUB/PDF fixtures under `tests/` with embedded-source/licensing notes in `tests/README.md`.
- [x] Verified dark appearance survives app relaunch and added the Room v1→v2→v3→v4 migrations for open date, book metadata, cover/progress, and metadata-load state.
- [x] Upgraded Readium and its PDFium adapter to 3.4.0. Configured PDFium to retain continuous scrolling after the upstream default changed to paginated; verified page display and page selection on API 37.
- [x] Added reader controls for Android System TTS on EPUB, long-press “Read from here” text selection, spoken-text highlighting/follow-along, page/location scrubbing and jump, table of contents, and full-publication search.
- [x] Verified on the API 37 AVD that EPUB speech invokes Android System TTS, selection starts narration at the selected text, search navigates to a match, TOC selection works, and the PDF page picker jumps to page 1. PDF speech is unavailable until PDF text-to-locator support is validated.
- [x] Moved EPUB narration ownership to a MediaSessionService; added media notification/lock-screen/car controls, a configurable library mini-player grace period, Bluetooth disconnect/reconnect policy, and audio-focus interruption behavior. The default grace is five minutes; Bluetooth reconnect and long-interruption resume are opt-in. The current focus/resume adjustment passes build checks but still needs device verification (see blockers).
- [x] Pinned Pocket TTS LiteRT runtime sources to upstream commit `0354739b7af355da804fd676b69ac03a67aa3178`, integrated its engine/service modules, added Pocket engine/voice settings, and added cancellable model installation from the pinned `2026.09` manifest.
- [x] Added `scripts/provision-pocket-models.ps1` and per-file SHA-256 checks; installed and verified the 15 required Pocket model files in EpubReader's app-specific files directory on the connected Pixel 10.
- [x] Confirmed on Pixel 10 (Android 17 / API 37) that Android discovers EpubReader's Pocket TTS service and that the engine connects and completes its startup preload while opening the supplied EPUB through the reader.
- [x] Reworked the EPUB reader into a continuous vertical scroller that fills the window by default (no page-flip, wide top/bottom margins, or bottom scrollbar); kept the location control for navigation and scrubbing.
- [x] Made Pocket voices dynamic: voice files are discovered from the model pack's `voices/` subdirectory and shown in a Settings dropdown, the hardcoded voice list was removed, and the text-size slider now applies to narration.
- [x] Added double-tap-to-read: a double tap on EPUB text resolves the sentence at the touch point (`caretRangeFromPoint`) and starts narration there with the yellow sentence highlight. Verified on Pixel 10.
- [x] Added narration auto-follow: the spoken sentence is kept inside a middle band, a real touch drag stops following and shows a recenter button, and recentering resumes following. Verified on Pixel 10 (95s+ of following without a false stop; drag stops follow; recenter works and follow resumes).
- [x] Declared `POST_NOTIFICATIONS` and requested it at launch so the Media3 background-playback notification can be posted on Android 13+. Confirmed the foreground notification now appears on Pixel 10.

## Immediate next steps

1. Make Media3 own the playback notification so it shows the mini-player controls (play/pause + skip back/forward). The app's manual "Preparing book playback" foreground notification is currently shown instead; Media3 only publishes its notification once it can resolve a connected notification controller and a non-empty player timeline, so verify the TTS session adapter's timeline/controller and stop posting the placeholder notification once Media3 can take over.
2. Verify notification-shade and lock-screen controls during Pocket narration on Pixel 10 (play/pause, skip back/forward, stop) and confirm media-button/Bluetooth routing.
3. Repeat the library, appearance, EPUB, and PDF smoke tests on a stable API 36 AVD and test the window-inset matrix.
4. Replace percentage-only restoration with persisted Readium locator JSON and validate process recreation.
5. Verify System TTS interruption/cancellation, Bluetooth headset disconnect/reconnect, and audio-focus behavior on an API 36 AVD and representative Bluetooth devices.
6. Audit PdfiumAndroid/JitPack licensing and native ABI/16 KB page-size support; validate more representative PDFs before claiming broader support.
7. Confirm audible Pocket TTS narration from the EPUB on Pixel 10; test per-book voice selection, pause/stop/cancellation, offline behavior, first-audio latency, and memory use.

## Blockers / pending verification

- Readium PDF extraction/TTS locator mapping must be proven with representative PDFs.
- Readium 3.4.0 and Pocket TTS LiteRT commit `0354739b7af355da804fd676b69ac03a67aa3178` are pinned. Pocket service startup and model installation are confirmed on Pixel 10, but audible end-to-end Pocket narration and performance/cancellation checks are still pending.
- SAF folder accessibility depends on Android's picker and the document provider; the basic local-folder grant and scan flow worked on the API 37 AVD. Repeat on API 36 and document provider constraints.
- Readium 3.3.0 opened the supplied EPUB fixtures directly through persisted `content://` URIs on the API 37 AVD. Percentage-based progress restores the PDF page position; serialized Readium locator persistence and reader process restoration remain unimplemented.
- The supplied searchable PDF renders and resumes at the saved percentage with Readium's PDFium adapter. PDF TTS text extraction and synchronized highlighting remain unimplemented; scanned-PDF OCR is out of scope.
- AGP 9.4 currently requires opting out of its new DSL to use Kotlin 2.4.20's external Android plugin. The opt-out is deprecated and must be revisited when AGP/Kotlin plugin compatibility improves.
- The audio-focus/long-interruption refactor now builds and passes unit tests, debug assembly, and lint. Earlier Pixel logs showed both the service and Readium requesting audio focus; confirm on-device that the service now relies only on Readium's Media3 focus handling and does not compete with it.
- Bluetooth disconnect/reconnect, notification/lock-screen/car behavior, and interruption recovery still need device verification. In-ear removal detection is not implemented because generic Bluetooth routing does not expose a reliable cross-device wear-state signal.
- The media notification currently shows the service's manual "Preparing book playback" foreground notification rather than Media3's media-style notification, so notification/lock-screen transport controls are not yet visible. `POST_NOTIFICATIONS` is now granted, so the remaining issue is Media3 taking over the notification (connected notification controller + non-empty player timeline).
