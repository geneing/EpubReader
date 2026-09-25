# Pocket TTS LiteRT source snapshot

- Upstream: <https://github.com/geneing/PocketTTS-LiteRT>
- Pinned commit: `0354739b7af355da804fd676b69ac03a67aa3178` (2026-09-24)
- Model API/version: `1` / `2026.09` (`app/src/main/assets/pockettts/models.json`)
- Upstream software license: MIT; see `LICENSE`.

Only upstream `pockettts-core` and `pockettts-service` are vendored. Their Kotlin
runtime sources are preserved from the pinned commit. The two module Gradle
scripts use the root project's pinned plugin versions and the non-deprecated
Kotlin compiler-options DSL so they build with this app's Kotlin 2.4.20 / AGP
9.4.0 toolchain. Model graphs, voices, and LiteRT Google Tensor dispatch
binaries are not stored here or in the APK. Provision model files to the app's
external files directory using `scripts/provision-pocket-models.ps1`; downloaded
release variants are SHA-256 checked against the pinned model manifest.

The model weights and individual voices have separate upstream licenses and
attribution requirements. See `docs/RESEARCH.md` and `docs/TECHNICAL_DESIGN.md`.
