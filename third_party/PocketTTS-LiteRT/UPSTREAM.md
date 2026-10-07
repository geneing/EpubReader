# Pocket TTS LiteRT source snapshot

- Upstream: <https://github.com/geneing/PocketTTS-LiteRT>
- Snapshot: upstream `main` at `be0e64c` plus its uncommitted Tensor G5 FlowLM
  NPU working-tree changes (captured 2026-10-06). Supersedes the previous
  `2dba838` snapshot, which had no NPU support at all.
- Model API/version: `1` / `2026.09` (`app/src/main/assets/pockettts/models.json`)
- Upstream software license: MIT; see `LICENSE`.

Only upstream `pockettts-core` and `pockettts-service` are vendored. Their Kotlin
runtime sources (`src/main`), including `src/main/cpp/npu_slice_cache.cpp` and
`src/main/cpp/g5_performance.cpp`, are preserved from the snapshot. The two module
Gradle scripts use the root project's pinned plugin versions and the non-deprecated
Kotlin compiler-options DSL so they build with this app's Kotlin 2.4.20 / AGP
9.4.0 toolchain; the core script additionally builds the `pockettts_kv_slice`
CMake target and enables `BuildConfig`.

Model graphs, voices, and LiteRT Google Tensor dispatch binaries are not stored
here or in the APK. Provision model files to the app's external files directory
using `scripts/provision-pocket-models.ps1`; downloaded release variants are
SHA-256 checked against the pinned model manifest. Tensor G5 NPU execution also
requires the dispatch shim `libLiteRtDispatch_GoogleTensor.so`, which is fetched
rather than committed by `scripts/fetch-google-tensor-dispatch.ps1` from the
matching LiteRT `v2.2.0` GitHub release.

Voice files: bundled presets use the flat `pt_voice_<name>.bin` convention at the
model-directory root, while voices fetched/cloned at runtime (`download_voices.py`,
`create_voice.py`) live one level below in an app-owned `voices/` directory.
Discovery is `dev.pockettts.VoiceCatalog.installed(models)`, which reads both
locations; the app calls `VoiceCatalog.ensureDir(models)` so `voices/` exists
app-owned before adb pushes a cache into it, and lists voices from the catalogue
so a pushed `pt_voice_*.bin` is offered without a code change. `requiredFiles`
only covers the model graphs plus the bundled voices; extra caches are optional.

On Tensor G5 the FlowLM default is the AOT graph
`pt_flowlm_fused_st16_ffn12_contiguous_no_truncation_g5.tflite`, provisioned like
the other graphs and driven through the position-major persistent NPU cache with
`G5PerformanceModel` HIGH_PERFORMANCE mode. When that graph or the dispatch shim
is absent, FlowLM follows the CPU/GPU placement policy instead. This graph is not
yet part of the upstream `models.json` release variants (`install_to_device.sh`
does not carry it either), so it is host-provisioned only.

The model weights and individual voices have separate upstream licenses and
attribution requirements. See `docs/RESEARCH.md` and `docs/TECHNICAL_DESIGN.md`.
