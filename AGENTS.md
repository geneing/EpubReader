# EpubReader — Agent and contributor guide

## Product direction

- Build an Android-first EPUB/PDF TTS reader. Treat `docs/PRODUCT_DESIGN.md` and `docs/TECHNICAL_DESIGN.md` as the product and architecture references.
- Support DRM-free EPUB 2/3 and unencrypted, text-based PDF. Image-only/scanned PDF OCR is out of the initial scope.
- The only supported narration providers are Android system TTS and Pocket TTS LiteRT from `geneing/PocketTTS-LiteRT`. Do not add cloud speech providers or translation without a product decision.
- The first supported OS version is Android 16 / API 36. The compile and target SDK should use the latest stable Android SDK available when implementation is performed. Do not target an Android preview SDK for production builds.
- The product name in project documentation is **EpubReader** until changed by the owner.

## Technology and dependency policy

- Use Kotlin, Jetpack Compose, Material 3, and Readium Kotlin Toolkit for EPUB/PDF publication parsing and navigation.
- Resolve the latest stable Kotlin, Android Studio, Android Gradle Plugin (AGP), Gradle, Compose BOM, and stable library releases that are mutually compatible at implementation time. Record pinned versions in the version catalog; never use dynamic `+` dependency versions.
- Readium and Pocket TTS are actively evolving. Pin a compatible stable Readium release and a specific Pocket TTS Git commit/release. Keep their compatibility and model-license notes in `docs/TECHNICAL_DESIGN.md` up to date.
- Follow the library's declared Android minimums and test its integration before reducing the app's API baseline. The app baseline is currently API 36 regardless of Pocket TTS's lower declared minimum.
- Use Java 17 bytecode unless a required dependency explicitly requires a later supported toolchain. Android Studio/AGP can use a newer bundled JDK where needed; do not change target bytecode casually.
- Keep secrets, signing credentials, local SDK paths, generated model weights, and generated audio out of version control.

## Architecture and implementation practices

- Keep UI state in ViewModels/state holders and business/data operations outside composables. Compose screens should render state and emit user actions.
- Keep publication operations behind reader/repository interfaces. Treat Readium `Publication`, `Locator`, and navigator instances as integration details rather than persistent database entities.
- Keep speech behind an app-owned provider abstraction. Persist the selected provider and relevant voice/rate preferences per book; route playback controls through a single playback/session owner.
- Preserve the Reader's current publication locator when navigating, pausing, stopping, or leaving the screen. Do not use display strings or page numbers alone as EPUB resume identifiers.
- Use Android's Storage Access Framework (SAF) as the library's source of truth. Let users register one or more book folders with `ACTION_OPEN_DOCUMENT_TREE`, persist read grants, and refer to book files by content URI. Do not copy book files into app-private storage or request broad shared-storage permissions.
- Support adding a single document via the system picker/share sheet as well as registered folders. Removing a book/folder from the app removes only its library entry/grant; never delete or move the user's source file.
- Scan registered folders on user request (and optionally offer a controlled refresh), track folder permission state, and recover from revoked grants, removed folders, provider outages, and moved/deleted files.
- Use an Android media session and media-style notification for ongoing playback; support background playback, headset/Bluetooth controls, audio focus, and Android foreground-service requirements.
- Make model download/install a user-visible, cancellable operation. Verify Pocket model manifests/checksums, keep model artifacts outside the APK, and provide useful states for absent/corrupt/incompatible files.
- For development/device tests, prefer uploading the pinned Pocket model pack from the host to the connected Pixel 10 with ADB. Cache verified model files in the app-specific model directory across launches; never commit weights or push them into source-controlled folders.
- Do not commit copyrighted sample books, model weights, generated audio, or voice assets. Preserve attribution and license notices for Readium, LiteRT, Pocket TTS, voices, and bundled third-party code.
- Use an Android Virtual Device (AVD) on the host as the primary development and regression environment. Exercise Android System TTS in the AVD, with its audio routed to host speakers. Use a physical Pixel 10 only for on-device Pocket TTS/LiteRT inference and performance checks. Windows host speech APIs are not Android TTS engines.
- Prefer narrow, meaningful tests for SAF grants/folder scanning, parsing, progress persistence, speech-provider behavior, and playback lifecycle. Add/adjust tests when changing these paths.

## Git workflow

- Keep the repository's current default branch stable. Create a focused branch for each feature, fix, or documentation change from the latest default branch; use prefixes such as `feature/`, `fix/`, `docs/`, and `chore/`.
- Commit frequently at coherent, reviewable checkpoints (for example, SAF folder registry, reader flow, or playback lifecycle), rather than accumulating a large uncommitted change set. Use concise commit messages with a clear scope; Conventional Commit style is preferred.
- Before each checkpoint, inspect `git status` and the staged diff, run the checks appropriate to the change, and stage only intended files. Keep generated builds, local config, book files, model weights, and audio out of commits.
- Do not force-push or rewrite shared history. Do not amend/reorder commits unless specifically requested.

## Windows build environment

The expected development host is Windows. Run Gradle from PowerShell using the Windows wrapper, and set the Android SDK path before Gradle. Run the app and most manual/instrumented checks on a host AVD; reserve the Pixel 10 for Pocket TTS hardware testing:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
./gradlew.bat :app:assembleDebug
```

Use the wrapper checked into the repository. Do not run Gradle from WSL. WSL is available for POSIX-only scripts (for example, Pocket TTS model conversion scripts); the Windows workspace is mounted under `/mnt/<drive>/...`.

For Pocket model testing, use the repository's development provisioning task/script (to be implemented) or `adb push` to place host-generated/pinned model files in the app-specific model cache on the Pixel 10. Verify hashes and preserve that cache between app launches; rebuilding/reinstalling should not require uploading the weights again unless the app was uninstalled, its data cleared, or the model version changed.

## Documentation expectations

- Update the product/technical docs when changing supported formats, speech providers, minimum Android version, model delivery, or user-facing playback behavior.
- Record research dates for facts that change over time (SDK, Kotlin, Android Studio, Readium, Pocket model releases).
- Do not present a feature as supported merely because a dependency has a similarly named API; validate it on representative EPUB/PDF documents and Android devices.
- Maintain the root `TODO.md` as the long-range, actionable backlog. Keep near-term next steps and milestone state in `Progress.md`; update it at meaningful checkpoints and when priorities/blockers change.
- Maintain root `LEARNINGS.md` as a concise, dated record of important decisions, discoveries, integration constraints, and bugs/pitfalls to avoid. Prefer durable lessons over a chronological chat log.
- Keep `README.md` as the entry point and link to these tracking files. When completing a milestone, update `TODO.md`/`Progress.md`/`LEARNINGS.md` as relevant in the same focused Git checkpoint.
