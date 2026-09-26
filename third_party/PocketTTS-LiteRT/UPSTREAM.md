# Pocket TTS LiteRT source snapshot

- Upstream: <https://github.com/geneing/PocketTTS-LiteRT>
- Pinned commit: `2dba83888706fb52339767670a36ef22348aecd4` (2026-09-25)
- Model API/version: `1` / `2026.09` (`app/src/main/assets/pockettts/models.json`)
- Upstream software license: MIT; see `LICENSE`.

Only upstream `pockettts-core` and `pockettts-service` are vendored. Their Kotlin
runtime sources (`src/main`) are preserved from the pinned commit. The two module
Gradle scripts use the root project's pinned plugin versions and the non-deprecated
Kotlin compiler-options DSL so they build with this app's Kotlin 2.4.20 / AGP
9.4.0 toolchain. Model graphs, voices, and LiteRT Google Tensor dispatch
binaries are not stored here or in the APK. Provision model files to the app's
external files directory using `scripts/provision-pocket-models.ps1`; downloaded
release variants are SHA-256 checked against the pinned model manifest.

Voice files: bundled presets use the flat `pt_voice_<name>.bin` convention at the
model-directory root, while voices fetched/cloned at runtime (`download_voices.py`,
`create_voice.py`) live one level below in an app-owned `voices/` directory.
Discovery is `dev.pockettts.VoiceCatalog.installed(models)`, which reads both
locations; the app calls `VoiceCatalog.ensureDir(models)` so `voices/` exists
app-owned before adb pushes a cache into it, and lists voices from the catalogue
so a pushed `pt_voice_*.bin` is offered without a code change. `requiredFiles`
only covers the model graphs plus the bundled voices; extra caches are optional.

The model weights and individual voices have separate upstream licenses and
attribution requirements. See `docs/RESEARCH.md` and `docs/TECHNICAL_DESIGN.md`.

