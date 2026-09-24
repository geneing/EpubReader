# Learnings — EpubReader

Record durable decisions, discoveries, integration constraints, and bugs/pitfalls to avoid. Date entries and revise them when verified implementation results supersede current assumptions.

## Decisions and discoveries (2026-09-23)

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

## Bugs and pitfalls to avoid

- Do not store a raw path or display filename as the durable book identity; SAF URIs/provider document IDs and persisted grants are required.
- Do not assume a persisted folder grant guarantees that the folder/file/provider stays present or online.
- Do not use broad storage permissions as a shortcut around user-selected SAF access.
- Do not place reader text/player controls under the status bar, camera hole/notch, navigation bar, or IME just to achieve an edge-to-edge look.
- Do not test Android playback solely with the Windows host speech service; exercise Android System TTS in the AVD.
- Do not assume AVD GPU/NPU behavior predicts Pocket TTS performance on the Pixel 10.
- Do not re-upload/re-download model weights for every test run, and do not put model artifacts in the APK or Git.
- Do not claim synchronized PDF sentence highlighting until extraction-order and locator mapping are validated.
