# Research notes

**Checked:** 2026-09-24

**Purpose:** Scope and technology baseline for EpubReader, using Evie/eVoice Reader as a product reference.

## 1. eVoice Reader (Evie) feature research

Sources: [eVoice Reader home](https://evoicereader.com/), [Help](https://evoicereader.com/help/), [About TTS](https://evoicereader.com/about-tts/), [About eBooks](https://evoicereader.com/about-ebooks/), and [Google Play listing](https://play.google.com/store/apps/details?id=com.lenntt.evoicereader).

The current public site/help describes these capabilities relevant to EpubReader:

- **Reading/library:** Android file picker, share/open from other apps, folder scan via the system picker, library management, last-book auto-reopen, saved reading position, metadata/cover and table of contents.
- **Reader interaction:** sentence/paragraph selection with “read from here,” current sentence highlight, auto-scroll to the spoken location, chapter navigation, and settings to control follow-along behavior.
- **Formats:** DRM-free EPUB and unencrypted PDF among a wider list. PDF can switch between original page layout and text reflow. The site explicitly explains that PDF extraction can fail or garble text, and recommends EPUB for TTS.
- **Playback:** narration continues with the screen off/in background; floating play control; notification and lock-screen controls; wired/Bluetooth media buttons; optional auto-start/stop on headset connection; chapter/sentence/approximately 30-second navigation.
- **Speech controls:** select engine/language/voice, remember preferences per book, adjust rate (site describes 50–200%), and configure punctuation/sentence pauses where the engine permits.
- **Listening utilities:** sleep timer, chapter/book remaining-time estimates, bookmarks with notes/categories/export, and pronunciation substitutions/regex/silencing.
- **Learning:** repeat each sentence and optionally pause for the user to repeat; cloud translation is an Evie paid feature and is intentionally excluded from this product's scope.
- **Cloud-only additions:** Evie has Polly/Azure/Evie cloud speech, offline caching of generated cloud audio, and paid translation. EpubReader explicitly does not adopt these features because it is constrained to System TTS and Pocket TTS.
- **Additional features:** recent site content mentions backup/restore; the Play listing mentions equalizer and Android Auto. These are not required for EpubReader MVP.

For EpubReader, the user specifically prefers registering book folders through Android's folder picker and retaining URI access over copying imported books into app-private storage. This matches Evie's folder-scan workflow while reducing app-owned file lifecycle overhead.

The proposed EpubReader MVP carries over the core local reader/listening jobs, not all Evie formats, cloud services, pricing/account systems, or visual design.

## 2. Stack and version findings

Versions change frequently. These are dated observations and must be rechecked before implementation/release.

| Area | Finding on 2026-09-23 | Source / implication |
|---|---|---|
| Android Studio | Quail 4, 2026.1.4, stable since 2026-09-01 | [Android latest updates](https://developer.android.com/latest-updates). |
| Android Gradle Plugin | 9.4.0 stable; Quail 4 supports AGP up to 9.4 | [AGP release compatibility](https://developer.android.com/build/releases/about-agp). Use the compatible Gradle required by selected AGP. |
| Kotlin | 2.4.20 latest stable, per Kotlin release page updated 2026-09-09 | [Kotlin releases](https://kotlinlang.org/docs/releases.html). Validate against Readium/Compose plugins. |
| Android OS SDK | Android 16 / API 36 is stable; Android 17 / API 37 is in beta (Beta 4.1 in latest-updates snapshot) | [Android 16](https://developer.android.com/about/versions/16), [Android 17](https://developer.android.com/about/versions/17/summary), [latest updates](https://developer.android.com/latest-updates). Product owner selected Android 16+ as the minimum, so API 36 is the initial minSdk baseline; use the latest stable compile/target SDK at implementation time. |
| Compose | Stable Compose BOM 2026.09.00, required to use the current stable Compose libraries | [Compose release notes](https://developer.android.com/jetpack/androidx/releases/compose), [Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping). Its resolved artifacts require compile SDK 37; min/target remain API 36. |
| Readium Kotlin Toolkit | 3.4.0, released 2026-09-11, is the latest stable release checked 2026-09-24 | [Readium releases](https://github.com/readium/kotlin-toolkit/releases), [3.4.0 docs](https://readium.org/kotlin-toolkit/3.4.0/). The docs state Readium is low-level; its TTS navigator supports any publication with a ContentService. |
| Pocket TTS-LiteRT | `geneing/PocketTTS-LiteRT` main branch is an active, MIT-licensed Android project with `pockettts-core`, `pockettts-service`, and `app` modules | [Repository](https://github.com/geneing/PocketTTS-LiteRT), [library guide](https://github.com/geneing/PocketTTS-LiteRT/blob/main/docs/library.md), [agent/build notes](https://github.com/geneing/PocketTTS-LiteRT/blob/main/AGENTS.md). Pin a commit; do not consume mutable main in a release build. |

### Important platform constraints

- The current Pocket TTS core/service modules declare minSdk 31; the product choice of API 36 minimum satisfies this.
- The current Pocket library README states LiteRT 2.2.0, large model packs, and a host/graph split. Benchmark representative supported devices; NPU acceleration is device-specific and cannot be assumed.
- The Readium guide's capability table reports EPUB TTS as implemented and PDF TTS as a less-complete/desired capability. Prove PDF extraction-to-locator mapping before promising EPUB-equivalent synchronized speech.
- Readium's current visual navigators are Android Fragments; newer Jetpack Compose Web Navigators are documented as alpha. Compose can still be the app shell around stable visual navigators.
- The Android 17 preview SDK is not the product minimum and should not be necessary for release builds.
- The Compose BOM 2026.09.00 stable snapshot resolves Compose 1.12.1 artifacts that require compile SDK 37. The project can compile against API 37 while keeping `targetSdk`/`minSdk` at stable API 36; reassess once Android 17 is stable and the preview compile dependency is no longer needed.
- Readium 3.4.0's PDFium adapter renders searchable text PDFs with `PdfNavigatorFragment`. Its `AndroidPdfViewer` 3.2.8 and `PdfiumAndroid` 1.9.8 dependencies come from JitPack; Readium describes PdfiumAndroid as unmaintained. The 3.4.0 release changes the default PDF layout to paginated and fixes page positions/progression that were previously one page ahead. EpubReader explicitly enables continuous scrolling to preserve its prior layout. Rendering, page count, and page selection were smoke-tested against the supplied four-page PDF on API 37 with 3.4.0. PDF TTS/text-to-locator mapping and native-library release compatibility still need validation. See the [3.4.0 adapter setup and limitations](https://github.com/readium/kotlin-toolkit/blob/3.4.0/readium/adapters/pdfium/README.md), [migration guide](https://github.com/readium/kotlin-toolkit/blob/3.4.0/docs/migration-guide.md), and [AndroidPdfViewer 3.2.8](https://github.com/marain87/AndroidPdfViewer).
- Android 16-targeting apps cannot opt out of edge-to-edge on Android 16 devices. EpubReader should therefore use a conventional safe-area visual layout while correctly handling enforced window insets; edge-to-edge background drawing is not a requirement for reader content or controls. See [edge-to-edge views guidance](https://developer.android.com/develop/ui/views/layout/edge-to-edge) and [Android 16 target behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16).

## 3. Pocket TTS/LiteRT findings

The supplied repository currently documents:

- `pockettts-core`: LiteRT inference, model source/delivery, tokenizer/session and host-side orchestration.
- `pockettts-service`: Android `TextToSpeechService`, which can register Pocket TTS as a system engine; it streams PCM and maps stop to request cancellation.
- Preset voices in current docs: alba, marius, javert, charles, mary, eve. Current advertised voice service is English (`eng`); verify exact language/voice list in the pinned revision.
- Models are intended to be obtained separately from the app, with variant releases and SHA-256 manifest support. Current library docs estimate roughly 81 MB base plus an ~86 MB int8 LM variant; the repository README also describes a total on-device asset footprint around 225 MB in one configuration. Treat disk/RAM requirements as device/configuration dependent and display accurate current estimates.
- The repo describes model weights sourced from Kyutai. Its current README identifies upstream model weights as CC-BY-4.0 and voice assets as CC-BY-4.0/CC0, depending on voice. Retain notices and audit the precise selected artifact/voice terms before publishing.
- Voice cloning is not provided by the ungated model package described in the repo and is out of scope.
- Repository docs show a release download mechanism, but release availability must be checked before product implementation. Do not hard-code assumptions based on an example release tag.

## 4. Design implications

1. EPUB is the strongest first format because structure, TOC, locators, and TTS utterance mapping align well.
2. PDF needs separate quality gates. Page rendering and text extraction are different concerns; reflow may help narration but cannot fix missing text or poor source encoding. OCR is excluded from MVP by product decision.
3. Both engines should flow through one playback model and the same reader location state. Pocket's Android TTS service is a promising bridge into Readium's platform TTS path; confirm engine routing in a technical spike.
4. Background playback is a platform/media-session requirement, not just an always-on Compose screen. Use the media session for notification, lock-screen, and Bluetooth transport controls.
5. Model download is a required onboarding/settings flow, not an APK asset. Model attribution, checksum verification, available storage, and offline state belong in design from the outset.
6. Current upstream defaults imply API 31 for Pocket but the selected product minimum is API 36. No compatibility fork is needed for the stated requirement.
7. Development should be AVD-first on the Windows host, with Android System TTS exercised in the emulator. Reserve a physical Pixel 10 for actual Pocket LiteRT inference/performance; upload pinned weights from the host with ADB and reuse the phone's verified app-specific model cache.

## 5. Reference links

- Evie: [Home](https://evoicereader.com/), [Help](https://evoicereader.com/help/), [TTS guide](https://evoicereader.com/about-tts/), [ebook/PDF guide](https://evoicereader.com/about-ebooks/), [FAQ](https://evoicereader.com/faq/).
- Android: [Android Studio release channel](https://developer.android.com/studio/releases), [Android latest updates](https://developer.android.com/latest-updates), [Android 16 SDK](https://developer.android.com/about/versions/16), [Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk), [Compose BOM](https://developer.android.com/jetpack/androidx/compose-bom).
- Android UI: [Edge-to-edge views guidance](https://developer.android.com/develop/ui/views/layout/edge-to-edge), [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16), [Compose window insets](https://developer.android.com/develop/ui/compose/system/insets).
- Kotlin: [Kotlin releases](https://kotlinlang.org/docs/releases.html).
- Readium: [Kotlin Toolkit](https://github.com/readium/kotlin-toolkit), [3.4.0 documentation](https://readium.org/kotlin-toolkit/3.4.0/), [navigator capabilities](https://readium.org/kotlin-toolkit/3.4.0/guides/navigator/navigator), [3.4.0 migration guide](https://github.com/readium/kotlin-toolkit/blob/3.4.0/docs/migration-guide.md).
- Pocket: [PocketTTS-LiteRT](https://github.com/geneing/PocketTTS-LiteRT), [library integration](https://github.com/geneing/PocketTTS-LiteRT/blob/main/docs/library.md), [Kyutai Pocket TTS](https://github.com/kyutai-labs/pocket-tts).
