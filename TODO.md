# TODO — EpubReader roadmap

This is the long-term actionable backlog. `Progress.md` tracks the active milestone and immediate next actions. Check items off only after implementation and the appropriate verification are complete.

## Foundation and technical spikes

- [x] Bootstrap the Android app module with pinned Kotlin, Compose, AGP, Gradle, API 36 target/minimum, and Compose-required API 37 compile SDK.
- [ ] Create a host AVD baseline (API 36, Google Play image where needed) and document repeatable launch/test commands.
- [ ] Prototype SAF folder registration with persistable tree grants and list/refresh local EPUB/PDF files without copying. (Basic flow verified on API 37 AVD; repeat on API 36.)
- [ ] Verify Readium opens a book directly from a persisted SAF `content://` URI and restores the saved locator after process restart. (Direct URI open verified; locator restoration remains.)
- [ ] Integrate Readium EPUB/PDF navigators into the Compose shell and validate safe drawing insets on cutout/navigation configurations. (Both formats open on API 37 AVD; percentage resume and the basic inset layout are verified; device matrix remains.)
- [ ] Verify Android System TTS in the host AVD, including voice setup, utterance callbacks, cancellation, and audio routed to host speakers. (Readium TTS is integrated for foreground EPUB playback and was exercised on API 37; setup, cancellation, and host-speaker routing remain.)
- [ ] Complete the Readium TTS + Android System TTS integration, including sentence highlighting and resume behavior. (Foreground EPUB playback, spoken-utterance highlighting, and selected-text start are implemented; background lifecycle/media controls and exact locator resume remain.)
- [ ] Evaluate Readium PDFium support and prove extraction/reflow/locator mapping on representative text PDFs; document scanned PDF handling (no OCR in MVP). (Pinned 3.4.0 adapter; PDF rendering, continuous scroll, and progression update verified on API 37. PDF TTS/locator mapping and native-library release audit remain.)
- [x] Pin an audited PocketTTS-LiteRT revision and confirm a host-generated model pack can be installed via ADB on Pixel 10. (`geneing/PocketTTS-LiteRT` commit `0354739b7af355da804fd676b69ac03a67aa3178`; pinned model file hashes were checked on transfer.)
- [x] Add a repeatable host-to-Pixel 10 model provisioning command with hash verification and persistent app-specific model caching. (`scripts/provision-pocket-models.ps1`; model assets stay outside the APK.)
- [ ] Finish validating Pocket service discovery/selection and real Readium narration, then benchmark first audio, memory, cancellation, and offline use on Pixel 10. (The app installed all 15 required model files and the Pocket TTS service connected/preloaded on Pixel 10; end-to-end generated audio and performance/cancellation checks are not yet confirmed.)

## MVP application

- [ ] Build out the SAF folder registry: add/remove folders, persist grants, show access state, scan/refresh with progress, identify stale/missing items, and deduplicate safely. (Basic folder scan/persistence is implemented.)
- [ ] Add individual-document share/open intent support alongside the implemented single-file picker.
- [ ] Persist library metadata, SAF URIs, Readium locators, per-book speech preferences, and bookmarks in Room. (Library metadata, derived covers, and percentage progress persist; full serialized locators, per-book speech preferences, and bookmarks remain.)
- [ ] Implement Library/Home, Book Details/TOC, EPUB Reader, PDF Reader, Settings, and Bookmarks screens in Compose. (Library/recent/all files, folder management/detail, EPUB/PDF readers, global Settings, reader TOC/search/navigation controls are implemented; Book Details and bookmarks remain.)
- [ ] Implement text-based PDF page/reflow modes and report unreadable/scanned PDFs clearly without OCR.
- [ ] Complete and device-verify background narration with MediaSessionService, lock-screen/car controls, audio focus, headset handling, and lifecycle restoration. (Service/session, foreground notification, and route handling are implemented. Audio focus is delegated to Readium's Media3 player; the interruption/resume adjustment passes build checks but needs device verification. AVD and Bluetooth/car verification remain.)
- [ ] Complete the mini-player and per-book engine/voice selection. (A grace-period library mini-player with play/pause, utterance skip, stop, cover, and progress is implemented; background chapter navigation and per-book speech preferences remain.)
- [ ] Add a lifecycle-independent sleep timer and persist its deadline.
- [ ] Harden Pocket model management (download/update/verify/delete, interrupted downloads, and recovery) beyond the initial cancellable download UI and debug ADB provisioning.
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
- [ ] Validate Pocket TTS voice/model attribution and license notices for distribution; model weights and voices have terms separate from the vendored MIT-licensed runtime.
- [ ] Run final AVD matrix and Pixel 10 Pocket TTS device checks; document known format/device limitations.
