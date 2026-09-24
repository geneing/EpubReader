# TODO — EpubReader roadmap

This is the long-term actionable backlog. `Progress.md` tracks the active milestone and immediate next actions. Check items off only after implementation and the appropriate verification are complete.

## Foundation and technical spikes

- [ ] Create the Android project/modules with the current mutually compatible stable Kotlin, Compose, AGP, Gradle, and SDK toolchain.
- [ ] Create a host AVD baseline (API 36, Google Play image where needed) and document repeatable launch/test commands.
- [ ] Prototype SAF folder registration with persistable tree grants and list/refresh local EPUB/PDF files without copying.
- [ ] Verify Readium opens a book directly from a persisted SAF `content://` URI and restores the saved locator after process restart.
- [ ] Integrate the Readium EPUB navigator into the Compose shell and validate safe drawing insets on cutout/navigation configurations.
- [ ] Verify Android System TTS in the host AVD, including voice setup, utterance callbacks, cancellation, and audio routed to host speakers.
- [ ] Spike Readium TTS + Android System TTS sentence highlighting and resume behavior.
- [ ] Evaluate Readium PDFium support and prove extraction/reflow/locator mapping on representative text PDFs; document scanned PDF handling (no OCR in MVP).
- [ ] Pin an audited PocketTTS-LiteRT revision and confirm a host-generated/downloaded model pack can be installed via ADB on Pixel 10.
- [ ] Add a repeatable host-to-Pixel 10 model provisioning command with hash verification and persistent app-specific model caching.
- [ ] Validate Pocket service discovery/engine selection through the Readium TTS path and benchmark first audio, memory, cancellation, and offline use on Pixel 10.

## MVP application

- [ ] Build the SAF folder registry: add/remove folders, persist grants, show access state, scan/refresh with progress, identify stale/missing items, and deduplicate safely.
- [ ] Add individual-document picker and share/open intent support alongside folder registration.
- [ ] Persist library metadata, SAF URIs, Readium locators, per-book speech preferences, and bookmarks in Room.
- [ ] Implement Library/Home, Book Details/TOC, EPUB Reader, PDF Reader, Settings, and Bookmarks screens in Compose.
- [ ] Implement text-based PDF page/reflow modes and report unreadable/scanned PDFs clearly without OCR.
- [ ] Add background narration with MediaSessionService, notification/lock-screen controls, audio focus, headset controls, and playback state restoration.
- [ ] Add the mini-player, sentence/chapter navigation, rate controls, and per-book engine/voice selection.
- [ ] Add a lifecycle-independent sleep timer and persist its deadline.
- [ ] Implement production Pocket model management (download/update/verify/delete) in addition to debug ADB provisioning.
- [ ] Complete accessibility, font scaling, traditional inset-safe UI, phone/tablet adaptive layouts, and Android 17 beta compatibility checks.
- [ ] Add meaningful unit/instrumentation coverage for SAF grants, folder scans, locators, TTS state, media lifecycle, and model integrity.

## Post-MVP candidates

- [ ] Rich bookmarks with notes, categories/colors, export, and backup/restore.
- [ ] Pronunciation substitutions, regex rules, and configurable text silencing.
- [ ] Learn Mode with sentence repetition and optional repeat pauses.
- [ ] Improved PDF text cleanup/header-footer suppression after document corpus testing.
- [ ] Optional automatic folder refresh and user-configurable nested-folder scan policy.

## Release readiness

- [ ] Confirm app distribution plan, app license, model hosting/version policy, and all voice/model attribution requirements.
- [ ] Validate privacy disclosures for SAF providers and Android System TTS engine network behavior.
- [ ] Run final AVD matrix and Pixel 10 Pocket TTS device checks; document known format/device limitations.
