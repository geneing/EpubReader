# EpubReader — Technical design

**Status:** Architecture reference; first implementation checkpoint completed on 2026-09-24

**Updated:** 2026-09-24

## 1. Platform baseline

- Android application, minimum API 36 (Android 16), target the latest stable API when building/releasing. A newer preview `compileSdk` is acceptable when required by the selected stable libraries; it does not change `targetSdk` or `minSdk` behavior.
- Android 17 / API 37 is a beta in this research snapshot; keep it available for compatibility testing, but do not require a preview SDK to build production releases.
- Kotlin 2.4.20 is the latest stable release found during research. Android Studio Quail 4 (2026.1.4) and AGP 9.4.0 are the current stable releases found; select the newest mutually compatible stable Gradle/JDK/Compose/AndroidX releases when project modules are created and pin them.
- Java bytecode baseline: 17, subject to Readium/Pocket TTS integration requirements.
- Use version catalog (`gradle/libs.versions.toml`) as the single dependency version source. Use the Compose BOM for Compose library alignment. Avoid unbounded/dynamic versions.

The version numbers above are a dated baseline, not a promise that they will remain latest. Re-check current official release channels before major dependency upgrades or release, and validate Readium/Pocket compatibility.

### Current implementation snapshot

- The app is a Compose application plus two vendored Pocket TTS modules (`:pockettts-core` and `:pockettts-service`). Its version catalog pins Kotlin 2.4.20, AGP 9.4.0, Gradle 9.7.1, Compose BOM 2026.09.00, Readium 3.4.0, PDFium adapter 3.4.0, Media3 1.11.1, and LiteRT 2.2.0. `minSdk`/`targetSdk` are API 36; Compose currently requires `compileSdk` 37.
- Room stores SAF folder/book URIs, publication metadata, added/opened dates, a derived cover path, and reading percentage. Readium metadata and cover extraction is lazy for visible library rows. Original book files remain at the SAF URI.
- The first UI has recent/all files, recent/all folders, folder contents, and global Settings routes. Theme, EPUB font family/scale, speech engine/voice selection, and speech-rate preferences are persisted globally. Pocket model setup/status is surfaced in Settings.
- EPUB and PDF visual navigators have been manually exercised on an API 37 AVD with the project test fixtures. The reader provides page/location scrubbing and selection, TOC, and Readium publication search. Resume currently uses Readium's total progression percentage, not a serialized `Locator`.
- EPUB narration uses Readium 3.4.0's Android TTS navigator for System TTS and a custom Android `TextToSpeech` engine provider that explicitly binds to the app's Pocket service. Selection context actions start narration at the selected locator, and utterance locators drive visual following/highlighting. Background playback uses a MediaSessionService, notification/transport controls, and a configurable mini-player grace period. PDF speech, per-book speech preferences, exact locator persistence, and bookmarks remain unimplemented. Pocket engine startup and model provisioning were checked on Pixel 10; audible end-to-end synthesis, cancellation, and performance still need verification.
- Pocket source is pinned to `geneing/PocketTTS-LiteRT` commit `2dba83888706fb52339767670a36ef22348aecd4`; only its MIT-licensed core/service source is vendored under `third_party/`. Model manifest/API version is `2026.09`/`1`; model weights and voice files are downloaded or ADB-provisioned into app-specific storage and are not committed or bundled in the APK. Voice state files use the flat `pt_voice_<name>.bin` layout and installed voices are resolved with `dev.pockettts.VoiceCatalog.installed(models)`. See `third_party/PocketTTS-LiteRT/UPSTREAM.md` for provenance and `scripts/provision-pocket-models.ps1` for verified Pixel provisioning.

## 2. High-level architecture

Proposed Gradle structure (start with fewer modules if useful; extract only where boundaries are clear):

```text
:app                         Application, navigation graph, dependency wiring
:core:domain                 Book/progress/playback contracts and use cases
:core:data                   Room database, import repository, file/model storage
:core:designsystem           Compose theme and shared controls
:feature:library             Library/home/details/TOC screens
:feature:reader              Reader shell and Compose ↔ Readium integration
:feature:settings            App and per-book preferences / Pocket model setup
:playback                    Playback owner, Android MediaSessionService, notifications
:tts:api                     Engine/provider contract and shared speech settings
:tts:system                  Android TextToSpeech provider
:tts:pocket                  Adapter and pinned PocketTTS-LiteRT core/service modules
```

Keep a modular monolith. Do not introduce a network backend, account system, or a general plugin framework for two known local speech providers.

### Dependency direction

```text
feature UI -> domain contracts <- core:data
                    ^
                    |-- reader integration (Readium)
                    |-- playback service/media session
                    |-- TTS provider adapters
```

Use constructor injection; select a lightweight DI approach already compatible with the current AGP/Kotlin/Compose versions (manual/application-scoped graph is acceptable initially). Keep Android framework and Readium types at adapter boundaries.

## 3. Publication and reader integration

### Readium Kotlin Toolkit

Readium provides parsers, publication metadata/locators, EPUB/PDF navigators, and a TTS navigator. It is a low-level toolkit: EpubReader owns its library, import lifecycle, UI, persistence, and playback experience.

- Pin the latest stable Readium Kotlin Toolkit compatible with the chosen Kotlin/Gradle/SDK baseline (3.4.0 checked 2026-09-24; see the versioned migration notes before upgrades).
- EPUB uses the Readium streamer/publication and `EpubNavigatorFragment` or a compatible navigator. The rest of the app is Compose; bridge the Android `Fragment` navigator using a lifecycle-safe fragment container/AndroidView host. Keep navigator creation and destruction aligned with the owning reader destination.
- PDF uses Readium's PDF navigator with the pinned `readium-adapter-pdfium:3.4.0` adapter. EpubReader sets `PdfiumDefaults(scroll = true)` to retain continuous scrolling, which is no longer the adapter default. Version 3.4.0 also corrects PDF page locators/progression that were previously one page ahead; this app stores progression percentages rather than serialized locators, so verify existing PDF resume values when upgrading. The supplied searchable four-page PDF rendered, displayed its page count, and navigated by page selection on API 37 with 3.4.0. The adapter pulls the `marain87` AndroidPdfViewer/PdfiumAndroid artifacts from JitPack; upstream describes PdfiumAndroid as unmaintained, so keep the PDF integration isolated and re-evaluate maintenance, native ABI/16 KB page-size compatibility, and alternatives before release.
- Readium's `TtsNavigator` can provide publication-aware utterances and location synchronization. Use it for the shared EPUB TTS path where its Android TTS integration provides the desired behavior.
- Readium marks PDF TTS as less complete/under consideration in its current feature table. Treat PDF reflow/extraction and utterance highlighting as a proof-of-concept gate. Preserve a PDF-specific text extraction/position adapter seam so EPUB progress does not depend on PDF behavior.
- Readium's newer Compose-based Web Navigators are alpha in the research snapshot. Prefer the proven native Android navigator inside the Compose shell until the Compose navigator is stable and satisfies required TTS/decorations.

### Book import and file lifecycle

1. Make **registered book folders** the primary library source. Use `ActivityResultContracts.OpenDocumentTree` to request access to each folder the user chooses.
2. Persist the returned read grant with `ContentResolver.takePersistableUriPermission`. Store the tree URI and display metadata in a `BookFolderEntity`; never request broad storage permissions.
3. Enumerate children through `DocumentsContract` or an appropriate SAF abstraction, filter EPUB/PDF candidates, inspect MIME type/extension, and let Readium validate parseability. Support a user-triggered scan/refresh with progress, cancellation, and a review of newly discovered or missing entries. Make recursive nested-folder scanning explicit (initial proposal: recursive, user-triggered).
4. Keep a book's `content://` document URI and its owning registered-folder reference. Open it through Readium's Android `ContentResolver` resource support so publication parsing can read the original file in place. Do not copy, move, or rewrite the EPUB/PDF into app-private storage.
5. Also support one-off documents through `ActivityResultContracts.OpenDocument` and valid `ACTION_SEND`/`ACTION_VIEW` intents. Persist the individual document read grant where available; an item without a durable grant must be marked as temporary and reselected if access is lost.
6. Deduplicate by canonical provider/tree/document identity when possible, not filename alone. The same book in two locations can still be intentionally added twice; expose duplicates rather than silently merging unlike URIs.
7. Derive publication metadata and covers from Readium. Cache only generated thumbnails/metadata as app-owned derived data; source book files remain at their original URIs.
8. Removing a library entry removes only database metadata, cached derived thumbnails, and an unneeded persisted URI permission. It never deletes the source file or folder. Removing a tracked folder similarly removes its library association, not its contents.
9. Handle revoked grants, provider outages, disconnected removable storage, renamed/moved files, and deleted files as recoverable unavailable-source states. Offer regrant/reselect/remove actions and retain reading progress/bookmarks.
10. Show scan progress and recover gracefully from cancellation, provider errors, low device storage for derived thumbnails/model weights, and malformed archives.

SAF tree grants can be restricted by Android's system picker for certain locations. Explain which locations are selectable and provide an individual-file picker fallback when a provider does not offer a usable folder grant. Encourage on-device folders for offline reliability. SAF may expose third-party/document providers; their availability/network behavior is outside EpubReader's control, so do not label every selected source as offline.

## 4. TTS and playback design

### Provider API

Define app-facing contracts conceptually similar to:

```kotlin
interface SpeechProvider {
    val id: SpeechProviderId
    suspend fun initialize(): ProviderState
    suspend fun voices(languageTag: String?): List<SpeechVoice>
    suspend fun speak(text: String, options: SpeechOptions, listener: SpeechListener): SpeechRequest
    fun stop()
}
```

The actual contract should support the chosen Readium TTS API and Android `TextToSpeech` callback/cancellation model. Keep provider-specific voice identifiers, feature capabilities, and errors out of presentation code.

### System TTS provider

- Wrap Android `TextToSpeech` initialization, language/voice enumeration, utterance callbacks, rate/pitch, stop, and shutdown.
- Surface engine initialization and missing-data failures with an action to open system TTS settings/voice installation.
- Do not assume a voice is offline; that is a property of the installed engine/voice and may not be queryable consistently.

### Pocket TTS provider

- Pin `geneing/PocketTTS-LiteRT` at an audited commit. The current repository contains `pockettts-core` for direct engine calls and `pockettts-service` for an Android `TextToSpeechService` implementation.
- Integration: include the pinned core and service modules as source. Readium's `TtsNavigatorFactory` is supplied with an app-owned `TtsEngineProvider`; it opens `TextToSpeech` explicitly against EpubReader's Pocket engine package while sharing Readium's utterance lifecycle/location handling.
- Do not duplicate Pocket's graph/tokenizer/session implementation in this app. Keep upstream engine integration behind the provider adapter.
- The pinned upstream library declares minSdk 31. The product's API 36 minimum is compatible. Its merged service manifest registers an exported `TextToSpeechService` protected by `BIND_TTS_SERVICE` and the required TTS action/default category. Service discovery and engine startup were confirmed on Pixel 10 / Android 17 beta; actual synthesis still needs verification.
- Initialize/compile the engine off the main thread. Model initialization may allocate substantial native memory; retain only one process-scoped engine, release it when appropriate, and serialize engine operations as upstream requires.
- Model delivery uses the pinned `2026.09` manifest and `ReleaseModelSource`; the manifest is bundled, not the model weights. Downloads use a cancellable, resumable transport and upstream SHA-256 verification. Availability of the upstream release endpoint, free-space handling, and corrupt-file recovery must be validated before shipping. Debug ADB provisioning has per-file checksum checks before and after transfer.
- Make voice options come from `PocketTts.VOICES` for the pinned version. Current repository documentation describes English preset voices; unsupported language books should offer System TTS rather than implying Pocket support.
- Upstream model files are large (roughly 160–225 MB depending on variants/assets in the current docs). Keep them outside the APK and do not bundle large model files in Git.
- **Development/test provisioning:** Generate/download the exact pinned model pack on the Windows host (using WSL only if upstream conversion scripts require POSIX), then run `scripts/provision-pocket-models.ps1` to upload required files to EpubReader's app-specific files directory. The script validates each source hash against the checked-in manifest and verifies each transferred device hash.
- Keep uploaded files cached on the phone across app launches, debug builds, and app updates. Do not redownload/re-upload on each test run. Android uninstall or clear-data removes app-specific cache, so provide a repeatable host-side provisioning command. Do not write test weights to Git, the APK, or a public release artifact.
- The Pixel 10 is the only required physical device for actual Pocket LiteRT inference/performance testing. Use mocks/unit tests and AVD coverage for app states/model metadata; do not gate ordinary UI development on emulator GPU/NPU support.

If Pocket's Android system-service path proves unsuitable for in-process selection, use its direct core API behind `SpeechProvider`, but implement and test Readium utterance/location callbacks explicitly. Keep one of these paths as the source of truth; never synthesize the same utterance both through Readium and an independent player.

### Readium utterances and synchronization

- The Readium TTS navigator is the owner of publication sequence, utterance boundaries, and locator progression.
- Bridge utterances to the selected `SpeechProvider`; observe Readium playback/location flows and publish compact UI state through a playback state holder.
- Apply current-sentence decorations/highlights through Readium's decoration APIs where available. Ensure the visible position is saved when the location changes, on pause/stop, and when the activity is backgrounded.
- For PDF, define and test a deterministic extraction order and mapping from extracted text ranges back to page/locator positions. Do not claim exact sentence highlighting if the mapping cannot be established.
- Sentence skip should operate on Readium utterances. Notification seek actions can jump to a nearby utterance boundary.

### Background playback

- Use Media3 MediaSession/MediaSessionService for a consistent playback notification, lock-screen controls, and external media controls. The pinned Readium Android TTS navigator adapts to Media3 `Player`, allowing its session to drive notification, headset, and automotive commands; validate these surfaces on device.
- Declare required foreground service type (`mediaPlayback`) and notification permissions/behavior for supported Android APIs.
- Let Readium's Media3 TTS session/player own audio-focus requests; do not add a competing focus requester in `BookPlaybackService`. The service applies the opt-in long-interruption policy, pauses on wired/Bluetooth output disconnect, and only resumes on Bluetooth reconnect when enabled and playback was active before disconnect. Generic in-ear detection is not portable. This focus ownership has not yet been re-verified on device after the service-side requester was removed.
- Wire play/pause/utterance skip/stop commands into the same playback owner used by the UI. Show media metadata and book progress for notification/lock-screen/car surfaces; device behavior remains a verification task.
- Sleep timer is a playback use case with lifecycle-independent persisted deadline, not a UI-screen timer. Persist/restore the deadline and stop cleanly at expiry.
- Ensure one active speech request at a time. A new utterance cancels the old request; stop/pause cancels queued/generating audio according to provider semantics.

## 5. Persistence model

Room is the recommended local database. Proposed entities:

- `BookEntity`: stable id, document content URI, optional owning folder id, provider/document id, format, publication title, author, language, derived cover reference, added/last-open timestamps, reading progression percentage, metadata-load state, and last-access status.
- `BookFolderEntity`: stable id, persisted tree URI, display name, provider authority, granted flags, recursive-scan preference, last-scan time/status, and user-facing permission state.
- `ReadingProgressEntity`: book id, serialized Readium locator, chapter/resource label, normalized progress, last-saved time.
- `BookSpeechPreferencesEntity`: book id, provider id, voice id, language tag, rate, pitch, provider-specific supported options.
- `BookmarkEntity`: id, book id, serialized locator, title/label, optional note/color/category, created time.
- `AppPreferencesEntity`: theme, default provider, default sleep duration, follow-along/auto-scroll, auto-reopen last book.
- `PocketModelStateEntity` or equivalent settings state: model version, installation state, required files/checksums, last error. Verified models remain in app-specific model cache storage; the database is not the artifact source of truth.

Use Readium locator serialization rather than relying on page number/string offsets. Store an app schema/version and migration tests for persisted locator/preference changes.

## 6. Compose state and navigation

- Use a single-activity Compose app shell and typed navigation destinations for Library, Book Details/TOC, Reader, Settings, and Bookmarks.
- ViewModels expose immutable UI state (`StateFlow`) and accept user intents. Long-running import/model operations expose progress and cancellation.
- Readium visual navigators are Android fragments: contain them in a lifecycle-aware host, avoid recreating on every Compose recomposition, and explicitly restore publication/locator when configuration changes.
- Keep player state process/service-owned and UI-observable. The mini-player and notification must share the same source of truth.
- Android 16 targeting API 36 enforces edge-to-edge behavior; do not fight the platform or place controls/text behind system areas. Use a traditional, inset-safe visual layout: apply `WindowInsets.safeDrawing` (or equivalent) to content, keep the top app bar below status/camera cutouts, reserve space above navigation/gesture insets for controls, and handle IME insets on forms. System-bar backgrounds may extend behind bars for continuity, but interactive/content surfaces should respect safe bounds.
- Validate display cutouts (center/side hole punch, notch), gesture and three-button navigation, landscape, split-screen, IME, and varied aspect ratios using AVD configurations. Use adaptive window-size layouts for tablets/foldables and maintain readable text widths.
- Support Android predictive back and large font scales.

## 7. Error and lifecycle behavior

- Missing/invalid document: show parse/open error and retain the library record for removal/retry.
- Source gone/permission revoked/provider unavailable (SAF-backed): offer regrant/reselect/remove; retain progress and bookmarks.
- TTS engine unavailable: keep the book open and provide engine setup/provider switch action.
- Pocket model missing/corrupt: pause before narration; offer verified download/repair and keep saved location.
- Speech synthesis error: stop at a known locator, show retry/switch-engine action, and prevent silent jumps.
- Process death: persist locator/preferences/timer and rebuild navigator/session from durable state; do not serialize live engine/navigator instances.
- Background service teardown: persist final location and release TTS/MediaSession resources deterministically.

## 8. Security, privacy, and licensing

- EpubReader itself does not upload or sync book content, progress, or utterance text. A selected Android System TTS engine may use network services according to its own configuration; make this distinction clear where possible. Pocket model download is the app's planned network use; engine behavior and model URL must be audited at the pinned commit.
- Use persisted SAF grants for book folders and document URIs. Do not copy book files to app-private storage or request broad storage permissions. Use app-specific storage only for Pocket model cache, generated thumbnails, and other derived app data.
- Restrict exported services/components to platform integration needs; Pocket's `TextToSpeechService` must follow the Android service permission and intent-filter contract.
- Preserve this repository's BSD-3-Clause license. Readium is BSD-3-Clause; Pocket repo's license and vendored dependencies must be preserved. Pocket base model weights are identified upstream as CC-BY-4.0 and the voice assets have their own CC-BY-4.0 / CC0 terms; include required attribution/notices and check every model/voice before distribution.
- Do not ship model weights until artifact checksums, licenses, version support policy, and hosting availability are confirmed.

## 9. Quality strategy

- Unit tests: MIME/file-type detection, SAF URI/folder registry identity, scan filtering/deduplication, grant-state mapping, progress/locator serialization, per-book preferences, sleep deadline behavior, speech-provider state mapping, PDF text cleanup/range mapping.
- Primary development and regression environment: Android Studio AVDs on the Windows host (API 36 stable; add current Android 17 beta AVD for forward compatibility). Test SAF folder/document grants, Compose/window insets, library, reader, lifecycle, and Android System TTS on the AVD. Use a Google Play-capable emulator image and install/configure Android Speech Services as needed; route emulator audio to host speakers.
- Physical-device policy: Pixel 10 is required only for real Pocket TTS/LiteRT inference, model cache, audio quality, and performance validation. Provision weights from the host with ADB and retain the verified cache across runs. Run ordinary System TTS, Compose, SAF, and navigation regression tests on AVDs.
- Instrumentation tests: SAF/open/share flows, Readium EPUB open/navigation/locator restoration, background service lifecycle, notification/media commands, engine switching, Pocket model manifest/hash logic, and model-download retry/corrupt-checksum behavior.
- Manual matrix: API 36 and current Android 17 beta; phone and tablet/foldable AVD sizes; System TTS installed/missing voice; Pixel 10 Pocket model absent/present; network unavailable; screen off; wired/Bluetooth media keys; camera cutout types; gesture and three-button navigation.
- EPUB fixtures should include EPUB2/3, RTL, long chapters, images, fixed layout, missing TOC, and malformed archives. PDFs should include searchable text, multi-column text, scanned image-only, encrypted, and unusual encodings.
- Keep copyrighted books and production model binaries out of test fixtures; use owned/public-domain/appropriately licensed minimal documents.

## 10. Implementation sequence

1. Bootstrap Compose app, version catalog, API 36 baseline, CI/build checks, and app navigation shell.
2. Integrate Readium EPUB/PDF parsing and navigators; implement SAF folder registry, persisted tree grants, in-place URI reading, lazy metadata/cover caching, Room library/progress, and percentage-based position restoration. Validate on AVD before adding device-specific work.
3. Readium TTS with Android System TTS, selected-locator start, and spoken-text highlighting is integrated. Background playback, MediaSessionService, notification/lock-screen/car commands, audio focus, and Bluetooth handling are implemented; validate on API 36 AVD and real routes, then continue voice/cancellation checks.
4. Add a persistent sleep timer and validate restoration/service teardown under process death.
5. The PDFium navigator is integrated and renders the supplied searchable-text fixture. Continue proving text extraction/reflow/narration mapping on varied PDFs before making PDF TTS or synchronized-highlighting claims.
6. Complete Pocket TTS verification: exercise audible Readium narration, voice switching, cancellation, offline use, model download/recovery, memory, and first-audio latency on Pixel 10. Keep non-inference work on AVD.
7. Finish library/reader/preferences/bookmarks UX, accessibility, error states, and device coverage.

## 11. Architecture decisions to revisit

- **Production model hosting:** Current repository docs describe release-based model delivery, but confirm a production release exists and the app is permitted/reliable to fetch it. Keep URL/version/checksum configurable through a trusted release manifest. Development/testing uses host-provisioned ADB files and a persistent Pixel 10 cache.
- **SAF provider coverage:** Validate folder grants against Android's DocumentsUI and common local providers on AVD/API 36; document provider-specific limitations and retain single-file picker fallback.
- **Pocket integration boundary:** The two modules are currently source-included from the pinned snapshot. Assess a published Maven artifact or standalone included build only if upstream starts publishing a stable library.
- **PDF narration:** Readium's PDF TTS completeness is limited. Make it a validated release gate; if range mapping is not reliable, allow page reading with clear extraction limitations and defer synchronized highlighting.
- **Compose navigator:** Revisit Readium's Compose Web Navigator after it exits alpha and can cover EPUB, TTS, and decorations.

## 12. Git and development workflow

- Keep the repository's default branch releasable. Work on focused branches created from its latest commit: `feature/<short-name>`, `fix/<short-name>`, `docs/<short-name>`, or `chore/<short-name>`.
- Commit each completed, reviewable slice frequently (for example, folder registration and scanning; EPUB resume; background media session; Pocket device provisioning). Before committing, inspect status/diff, run the relevant AVD tests/checks, and stage only the intended files.
- Keep the AVD as the quick feedback loop. Do not wait for physical Pocket hardware to finish UI, SAF, System TTS, or reader work.
- Keep generated AVD files, build output, test books, model binaries, and audio recordings out of Git. Never force-push or rewrite shared history.
