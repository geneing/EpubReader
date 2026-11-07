# Pocket TTS LiteRT source snapshot

- Upstream: <https://github.com/geneing/PocketTTS-LiteRT>
- Snapshot: upstream `main` at `5fa2587` (Pixel 11 / Tensor G6 retarget,
  captured 2026-11-07). Supersedes the `be0e64c` + Tensor G5 working-tree
  snapshot, which had no G6 support and targeted LiteRT 2.2.0.
- Model API/version: `1` / `2026.09` (`app/src/main/assets/pockettts/models.json`)
- Upstream software license: MIT; see `LICENSE`.

Only upstream `pockettts-core` and `pockettts-service` are vendored. Their Kotlin
runtime sources (`src/main`), including `src/main/cpp/npu_slice_cache.cpp` and
`src/main/cpp/g5_performance.cpp`, are preserved from the snapshot. The two module
Gradle scripts use the root project's pinned plugin versions and the
non-deprecated Kotlin compiler-options DSL so they build with this app's Kotlin
2.4.20 / AGP 9.4.0 toolchain; the core script additionally builds the
`pockettts_kv_slice` CMake target and enables `BuildConfig`.

Model graphs, voices, and LiteRT Google Tensor runtimes are not stored here or in
the APK. Provision model files to the app's external files directory using
`scripts/provision-pocket-models.ps1`; downloaded release variants are SHA-256
checked against the pinned model manifest. The Tensor NPU dispatch shim
`libLiteRtDispatch_GoogleTensor.so` and the on-device compiler plugin now come
from the pinned Maven AARs (`litert:2.3.2`, `litert-gpu:2.3.2`,
`litert-npu-runtime-google-tensor:2.3.2`), so no manual download is needed.

Voice files: bundled presets use the flat `pt_voice_<name>.bin` convention at the
model-directory root, while voices fetched/cloned at runtime (`download_voices.py`,
`create_voice.py`) live one level below in an app-owned `voices/` directory.
Discovery is `dev.pockettts.VoiceCatalog.installed(models)`, which reads both
locations; the app calls `VoiceCatalog.ensureDir(models)` so `voices/` exists
app-owned before adb pushes a cache into it, and lists voices from the catalogue
so a pushed `pt_voice_*.bin` is offered without a code change. `requiredFiles`
only covers the model graphs plus the bundled voices; extra caches are optional.

On Pixel 11 / Tensor G6 the default placement is CPU for the int8 FlowLM
(`pt_flowlm_fused_dyn8_all.tflite`) and the Mimi decoder transformer, and GPU for
SEANet. The Tensor NPU path is now **opt-in** (`force_npu.txt` containing `lm`)
and uses `pt_flowlm_fused_dyn8_all_contiguous_jit.tflite`, which the 2.3.2
runtime JIT-compiles on device. AOT `_g6` graphs produced by the available 2.3.0
compiler SDK do not load on the public 2.3.x runtime, and the G6 compiler cannot
build a decoder-transformer graph at all; the measured JIT path is also slower
than CPU int8. `g5HighPerformance` stays off because the native
HIGH_PERFORMANCE probe fails on G6.

The model weights and individual voices have separate upstream licenses and
attribution requirements. See `docs/RESEARCH.md` and `docs/TECHNICAL_DESIGN.md`.
