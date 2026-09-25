# EpubReader

An Android EPUB and PDF reader in active development, with text-to-speech narration. The product takes interaction cues from Evie / eVoice Reader while using its own name, visual identity, and implementation.

## Product scope

- DRM-free EPUB 2/3 and text-based, unencrypted PDF.
- Android system Text-to-Speech and Pocket TTS (LiteRT) only.
- Reads book files directly from user-selected SAF folders without copying them into app-private storage; no app account, cloud speech, translation, or ads. Availability depends on the selected document provider.
- Android 16 (API 36) or newer as the initial minimum platform.
- Jetpack Compose for application UI; Readium Kotlin Toolkit for publication parsing/navigation.
- Traditional, inset-safe screen layout that works around Android status bars, navigation bars, camera cutouts, and IME across phones; Android 16 edge-to-edge platform behavior is handled without placing important content under system areas.

Development is centered on host AVDs using Android System TTS. A physical Pixel 10 is reserved for Pocket TTS/LiteRT inference testing; development model weights are provisioned from the host and cached on the device.

## Project documents

- [Product design](docs/PRODUCT_DESIGN.md): feature set, user journeys, screens, and delivery scope.
- [Technical design](docs/TECHNICAL_DESIGN.md): app architecture, reader/TTS integration, data model, and implementation sequence.
- [Research notes](docs/RESEARCH.md): Evie feature research, technology versions, source links, and constraints.
- [Agent guidance](AGENTS.md): project conventions and build instructions for contributors and coding agents.
- [TODO](TODO.md): long-term roadmap and actionable backlog.
- [Progress](Progress.md): current milestone, completed checkpoints, and immediate next steps.
- [Learnings](LEARNINGS.md): durable decisions, discoveries, and pitfalls to avoid.

## Current status

Development is on `main`. The Compose app has a SAF-backed library with recent/all-files and folder views, global reading settings, and Readium EPUB/PDF readers. EPUB narration runs through a `MediaSessionService` with notification/lock-screen transport controls, selection- and double-tap-based start, spoken-sentence highlighting, and narration auto-follow. Both Android System TTS and Pocket TTS (LiteRT) are integrated. PDF narration is not implemented.

Manual EPUB/PDF reader fixtures and their source notes are in [`tests/`](tests/README.md).
