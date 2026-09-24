# Learnings — EpubReader

Record durable decisions, discoveries, integration constraints, and bugs/pitfalls to avoid. Date entries and revise them when verified implementation results supersede current assumptions.

## Decisions and discoveries (2026-09-23–24)

- **Book access uses SAF URIs; do not copy books.** Register user-selected directories via `ACTION_OPEN_DOCUMENT_TREE`, persist read grants, and read documents directly from `content://` URIs. Removing a library record must never delete/move the source. Missing/revoked grants must preserve progress and offer a recovery action.
- **Prefer on-device book folders for offline reliability.** SAF can expose providers with network or removable-media dependencies; their availability is outside the app's control. Do not promise offline access for every URI.
- **System TTS can have network behavior.** The app itself does not upload book content, but an installed Android TTS engine/voice may synthesize remotely. Avoid claiming that every System TTS voice is private/offline.
- **Android 16/API 36 enforces edge-to-edge for apps targeting API 36.** A conventional layout is still possible: consistently respect safe drawing/window insets, keep controls away from camera cutouts and system gesture areas, and use system-bar background drawing only for visual continuity. Do not assume a target-36 app can disable platform edge-to-edge behavior.
- **AVD-first testing is intentional.** Android System TTS runs through an Android engine in the AVD, with audio routed to the host. Windows Speech/SAPI is not an Android `TextToSpeech` engine. Actual Pocket LiteRT inference/performance is required only on Pixel 10.
- **Pocket development model workflow is host-to-device.** Provision the pinned model pack from the host with ADB, validate SHA-256, and reuse an app-specific device cache across runs. Uninstall/clear-data removes app-specific cache; ordinary app launches/rebuilds should not trigger another upload.
- **Keep books and weights out of Git.** SAF content remains user-owned, and Pocket graphs/voice assets are too large and carry licensing/attribution requirements. Keep test fixtures appropriately licensed and minimal.
- **Readium PDF narration needs a technical spike.** EPUB has a clearer structural/locator path; PDF reading order and mapping extracted utterances back to pages may be imperfect. Scanned PDFs have no extracted text and OCR is excluded from MVP.
- **Pocket TTS requirements:** upstream currently advertises English preset voices and large model packs (roughly 160–225 MB depending on variant). Verify the pinned revision's model files, supported devices, and CC-BY/CC0 notices before distribution.
- **Git workflow:** do feature work on focused branches and create small reviewable commits. Inspect status and staged diff before each checkpoint; never commit model files, books, audio, SDK paths, or build output.
- **Initial build stack:** AGP 9.4.0's built-in Kotlin/new DSL path currently conflicts with applying the standalone Kotlin Android Gradle plugin 2.4.20. The app temporarily opts out of both AGP features to keep the requested latest Kotlin compiler; those opt-outs are deprecated and should be removed after compatible support lands.
- **Compose compile SDK:** Stable Compose BOM 2026.09.00 artifacts require compile SDK 37. Keep min/target at stable API 36 and compile at 37 until Android 17 stabilizes or the requirement changes.
- **Readium URI integration:** Readium 3.3.0's `AssetRetriever(ContentResolver, HttpClient)` and `Uri.toAbsoluteUrl()` opened a generated EPUB directly from the persisted SAF tree URI on the API 37 AVD. `Try.getOrElse` is a Readium extension and must be imported from `org.readium.r2.shared.util`.
- **Readium navigator lifecycle:** The EPUB navigator is a Fragment and requires its `FragmentFactory` before adding/restoring the fragment. The initial reader returns to the library after Activity recreation because the live `Publication` is not yet process-restored; locator persistence is the next reader milestone.
- **PDFium adapter dependency:** Readium's 3.3.0 PDF adapter resolves `com.github.marain87:AndroidPdfViewer:3.2.8` and `PdfiumAndroid:1.9.8` from JitPack, so the repository needs a pinned JitPack repository in dependency resolution. The upstream adapter calls PdfiumAndroid unmaintained; keep the boundary replaceable and verify modern Android native/page-size support before release.
- **Readium 3.4 PDF migration:** 3.4.0 changes PDFium's default from continuous scroll to horizontal pagination; set `PdfiumDefaults(scroll = true)` when preserving scrolling behavior. It also corrects PDFium's off-by-one page positions and progression. The app only persists progression percentages, not PDFium `Locator` JSON, so the locator migration helper does not apply; nevertheless, revalidate saved PDF resume positions after the dependency upgrade.
- **Readium TTS navigator:** `readium-navigator-media-tts` supplies `AndroidTtsNavigatorFactory`; it can start at a visual navigator's visible or selected `Locator`, and its utterance locators can drive Readium decorations and visual-following. Declare the Android TTS service in manifest `<queries>`. Foreground reader playback is implemented; background continuation still requires the MediaSessionService integration.
- **Book-list enrichment:** Readium `Publication.coverFitting(Size)` and publication metadata can be extracted after opening a SAF URI. Cache only a small derived WebP cover under app files and persist its path; lazy-load visible rows so a large folder scan does not parse every whole book synchronously.
- **Reading percentage:** `Navigator.currentLocator.locations.totalProgression` supports live percentage updates. `Publication.locateProgression()` restores the saved percentage; this is a useful initial resume point, but exact locator JSON is still needed for precise restoration. Readium 3.4.0 fixes the PDFium page/progression offset present in 3.3.0.
- **Reader preferences:** Readium EPUB navigator supports `EpubPreferences` for font family, type scale, and light/dark theme. The app's global mode also updates Android system-bar icon contrast. Android System TTS voice/engine setup belongs in Android's TTS settings; the app still needs to connect stored rate/provider choices to a playback owner.

## Bugs and pitfalls to avoid

- Do not store a raw path or display filename as the durable book identity; SAF URIs/provider document IDs and persisted grants are required.
- Do not assume a persisted folder grant guarantees that the folder/file/provider stays present or online.
- Do not use broad storage permissions as a shortcut around user-selected SAF access.
- Do not place reader text/player controls under the status bar, camera hole/notch, navigation bar, or IME just to achieve an edge-to-edge look.
- Do not test Android playback solely with the Windows host speech service; exercise Android System TTS in the AVD.
- Do not assume AVD GPU/NPU behavior predicts Pocket TTS performance on the Pixel 10.
- Do not re-upload/re-download model weights for every test run, and do not put model artifacts in the APK or Git.
- Do not claim synchronized PDF sentence highlighting until extraction-order and locator mapping are validated.
- Keep SAF scanning off the main thread; DocumentFile/provider enumeration may block or fail while a provider is unavailable.
