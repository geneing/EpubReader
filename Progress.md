# Progress — EpubReader

Update this page at meaningful work checkpoints. Keep the active milestone, recent verified progress, blockers, and the next few actions concise.

## Current status

- **Stage:** Product/technical design; Android application code has not started.
- **Active branch:** `docs/reader-platform-design`.
- **Current milestone:** Establish the product and development baseline before bootstrapping the Android app.
- **Device strategy:** Host AVD is the default for UI, SAF, lifecycle, and Android System TTS. Pixel 10 is reserved for real Pocket TTS/LiteRT inference and performance.

## Completed

- [x] Researched eVoice Reader features and recorded source links/technology snapshot.
- [x] Created product design, technical design, contributor guidance, and project README.
- [x] Chose Android 16+ minimum, SAF folder-based book access without copying, and no scanned-PDF OCR in MVP.
- [x] Specified a traditional inset-safe UI that handles Android 16 enforced edge-to-edge behavior and camera cutouts.
- [x] Set AVD-first development/testing, Android System TTS in AVD, and Pixel 10-only physical Pocket inference checks.
- [x] Specified host-to-Pixel model provisioning via ADB and caching verified model weights on-device.
- [x] Added long-term TODO, progress tracking, and learnings-log requirements/files.

## Immediate next steps

1. Bootstrap the Android project and pin a mutually compatible stable toolchain/dependency set.
2. Create API 36 host AVD(s) and smoke-test System TTS audio output.
3. Prototype SAF folder grants and confirm Readium can parse an EPUB directly from the persisted content URI without copying.
4. Create a Pixel 10 provisioning spike for pinned Pocket weights and verify the device cache survives normal rebuilds/app updates.
5. Update this page and `LEARNINGS.md` with verified outcomes before moving on to full feature implementation.

## Blockers / pending verification

- Readium PDF extraction/TTS locator mapping must be proven with representative PDFs.
- Exact Readium/Pocket integration versions and the Pocket model artifact/release URL must be pinned and verified at implementation time.
- SAF folder accessibility depends on Android's picker and the document provider; test the intended local-folder workflow on AVD and document constraints.
