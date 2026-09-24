# EpubReader — Product design

**Status:** Initial product brief

**Research snapshot:** 2026-09-23

**Working name:** EpubReader

## 1. Product vision

EpubReader is a calm, private, Android reading app that lets people listen to EPUB books and PDF documents with their preferred speech engine. It reads books directly from user-selected SAF locations and does not take ownership of their files. It combines a capable visual reader with a dependable audio-book-like playback experience: narration continues in the background, playback can be controlled without opening the app, and the spoken position stays synchronized with the visible text whenever the publication format permits it.

Evie / eVoice Reader is a feature reference, not a visual or brand template. EpubReader should use its own interaction design and identity.

## 2. Product principles

1. **Reading first:** Opening a book and resuming where the reader stopped should be obvious and reliable.
2. **Audio is a first-class mode:** Play/pause, seek by sentence, chapter navigation, headset controls, and background playback are core behavior.
3. **Respect the user's files:** Read books directly from user-selected folders through Android's Storage Access Framework (SAF). EpubReader records access grants and library metadata; it does not copy, move, or delete book files.
4. **Private by default:** EpubReader does not upload or sync books. No account or network connection is required by the app to read once the selected engine/model is available; the selected Android System TTS engine may have its own network/offline behavior, and SAF sources depend on their provider being available.
5. **Be honest about source quality:** EPUB is the best format for structured narration. PDF is supported, but extraction quality varies; image-only PDFs are identified as unsupported for narration in v1.
6. **Accessible and adaptable:** Support system font scaling, TalkBack labels, high-contrast themes, large screens, and accessible control targets.
7. **Focused scope:** Only Android System TTS and Pocket TTS are supported; no cloud TTS or translation service is part of the initial product.

## 3. Target users and key jobs

- Readers who want to rest their eyes, listen while commuting, exercising, doing chores, or winding down.
- Readers with low vision, dyslexia, fatigue, or other accessibility needs who benefit from synchronized spoken and visual text.
- Language learners who want to listen to a selected sentence repeatedly (Pocket TTS language coverage is narrower than Android system voices).
- Users with mixed EPUB/PDF collections who need one library and a predictable way to resume.

Primary jobs:

- “Open this EPUB/PDF and start listening in a few taps.”
- “Pause from my headphones or lock screen, then resume at the same sentence.”
- “Find my place in a long book and estimate how much listening remains.”
- “Adjust the voice and pace for this book without changing every other book.”

## 4. Supported formats and explicit limits

### Initial release

- **EPUB 2 and EPUB 3:** DRM-free EPUBs that Readium can parse. Reflowable EPUB is the primary reading experience; fixed-layout EPUB may be opened when Readium supports its profile, with narration behavior validated separately.
- **PDF:** Unencrypted, text-based PDF supported by the selected Readium PDF adapter. Provide original page-layout viewing and a text-oriented/reflow reading option when extraction yields usable text.
- **Not supported:** DRM-protected publications, password-protected PDFs, MOBI/AZW/FB2/DOCX/RTF/HTML/TXT ingestion, cloud documents, and image-only/scanned PDF OCR.

Do not silently fail on a scanned or malformed PDF. Explain that selectable text was not found and that OCR is not available in this release. PDF extraction can produce reading-order, hyphenation, header/footer, and character-encoding errors; expose source-page context and a visual page mode when possible.

## 5. Narration engines

### Android System TTS

Use Android's platform `TextToSpeech` API and the engine/voices installed and configured on the device. Show available language/voice choices and guide the user to Android's TTS settings when a voice is missing or not downloaded. Availability, quality, latency, and offline support vary by installed engine and voice.

### Pocket TTS (LiteRT)

Integrate `geneing/PocketTTS-LiteRT` as a separate on-device engine. The repository exposes a reusable `pockettts-core` module and a `pockettts-service` Android `TextToSpeechService` module. The service approach makes Pocket TTS selectable through Android's engine APIs and lets Readium use the same speech interface as system TTS.

- For normal installs, model assets are downloaded on demand, verified, and cached in app-specific storage; they are not bundled in the APK. During development/device testing, weights are uploaded from the host to the Pixel 10 and retained in the app's model cache.
- Do not start Pocket narration until model setup is complete. Show download progress, storage size, retry, and delete-model actions.
- The current repository is English-focused and includes the preset voices documented upstream (currently alba, marius, javert, charles, mary, and eve). Present only voices actually shipped by the pinned upstream version.
- Explain that Pocket TTS processing is local. Voice cloning is not part of this app's initial scope.
- Rate/pitch ranges and behavior must follow the pinned Pocket engine and Android engine capabilities. Settings should not imply parity across providers.

The selected provider and supported voice settings are remembered per book. If the saved engine or voice is unavailable, preserve the saved preference, present a clear fallback prompt, and do not switch silently.

## 6. Feature scope and priority

### MVP / first usable release

- Register one or more book folders through Android's SAF folder picker, retain read access across app restarts, and scan/refresh those folders on request. Do not copy books into app storage.
- Encourage on-device folders for reliable offline access. If Android exposes a third-party/document provider, book availability follows that provider; do not promise that such a source is offline or locally stored.
- Open an individual EPUB/PDF with Android's document picker or accept a supported document shared to the app; retain a durable read grant when the provider permits it.
- Compact reading library with title/author/cover, last-read progress, sort/filter, remove-from-library, and continue-reading action.
- Folder management screen/list that shows registered folders and access status, supports adding/removing a folder and scanning for new/removed books, and reports inaccessible sources clearly.
- EPUB table of contents and chapter navigation.
- EPUB reading view with current sentence/utterance highlight, “read from here,” manual scroll/navigation, and persisted Readium locator.
- PDF page view and text/reflow view where extraction is available; page/chapter navigation and sentence-level follow-along when the extracted text can be mapped reliably.
- Playback controls: play/pause, previous/next sentence, previous/next chapter, playback speed, provider and voice selection.
- Playback that continues when the screen is off or the app is backgrounded, with a Media-style notification/lock-screen controls, audio focus, and wired/Bluetooth media-button controls.
- In-app mini-player visible outside the reader while a book is active.
- Sleep timer with a simple duration selector and a persistent timer state while narration runs.
- Resume last book and last position; allow the user to opt out of automatic re-open.
- Basic bookmarks (create, list, jump, delete) and per-book speech settings.
- Settings for theme, reader text appearance where supported, auto-scroll/highlight behavior, default engine, sleep timer defaults, and Pocket model management.
- Clear loading, no-content, extraction-error, missing-engine, and missing-model states.

### Follow-up releases

- Rich bookmarks with notes, categories/colors, export, and backup/restore.
- Pronunciation substitutions, optional regex rules, and text silencing. Rules should be explicit and book/global scope should be user-visible.
- Sleep bookmarks/“last position before sleep” shortcut.
- Sentence repetition / Learn Mode with configurable repeat count and optional pause for the reader.
- More advanced PDF text cleanup (header/footer suppression, column handling) based on real document testing.
- Rich library-folder management options such as automatic background rescans and nested-folder policies, based on user feedback.

### Out of scope unless separately approved

- Cloud speech, translation, account services, subscriptions, ads, OCR, DRM circumvention, audio export, additional book formats, or voice cloning.

## 7. Information architecture and screens

### Library / Home

- Traditional top app bar within the safe area: app name, search, settings/menu.
- “Continue listening/reading” card for the most recently opened title, showing cover, progress, and a prominent resume/play control.
- Library list/grid with compact cover, title, author, and progress. Include empty state with “Open a book.”
- Primary “Add folder” action opens Android's folder picker; an “Open file” action and ACTION_SEND/ACTION_VIEW remain available for individual documents.
- Overflow actions: open details, remove the library record (never delete the source file), rescan folders, and sort/filter.
- Show each registered folder, last scan state, and permission/error status. Let users review newly discovered books and stale/missing entries.

### Book details / Table of contents

- Cover and publication metadata, overall progress, estimated remaining time when enough text/speech-rate data exists.
- Table of contents with current location visibly marked and chapters tappable.
- For PDF, use document outline if present; otherwise show page entries/page navigation.
- Per-chapter estimated listening time is an enhancement; show “estimate” and avoid false precision.
- Access per-book engine/voice settings and bookmarks.

### Reader

- Reading surface takes visual priority; controls can collapse while listening.
- EPUB uses Readium rendering and navigation, preserving publication styles by default. Offer font size/theme/layout controls only where navigator preferences support them.
- PDF supports a page-layout mode for visually faithful pages and a text/reflow mode for narrow screens and TTS follow-along. State which mode is active.
- Highlight the current sentence/utterance when mapping is available; keep it visible with configurable auto-scroll. Manual navigation pauses auto-scroll temporarily.
- Tap/long-press a paragraph or sentence to expose “Read from here,” bookmark, and copy (when publication permissions and navigator selection APIs permit it).
- A bottom mini-player shows current title/chapter, play/pause, and previous/next sentence. Expand it into playback controls.

### Playback controls

- Play/pause, previous/next sentence, previous/next chapter, scrub/progress where meaningful, speed, voice selection, sleep timer, and TOC access.
- Show narration state distinctly: preparing engine/model, speaking, paused, buffering/processing, completed, or error.
- Sentence-based skip is preferred over arbitrary seconds because it keeps text location meaningful. Media-session seek actions can map to a configurable sentence/time window.

### Sleep timer

- Quick durations and custom duration; clear countdown and cancel action.
- When timer expires, stop narration and persist the last position. Do not force playback to stop merely because the user navigates away from the timer UI.

### Settings

- Default speech engine, app theme, default sleep duration, auto-resume behavior, auto-scroll/follow-along, and model storage management.
- Per-book speech engine, voice/language, rate, pitch (when supported), and reading appearance.
- Engine-specific controls should show capability differences rather than presenting unavailable settings as active.

## 8. Interaction and visual direction

- Use Material 3 components and current Compose adaptive guidance. Keep the reading surface quiet, with a warm light theme and a dark theme; user theme choice wins over system theme when explicitly set.
- Use a traditional safe-area layout: content starts below the status bar/camera cutout, stays above navigation/system gesture areas, and avoids placing titles, text, or controls behind a hole-punch/notch. Keep system bars visually coordinated with the app surface; use edge-to-edge drawing only as a platform-compatible background treatment, not as a layout requirement for app content.
- Android 16 enforces edge-to-edge for apps targeting API 36, so the app must handle window insets even though the visual design should remain conventional and inset-safe. Apply Compose `WindowInsets`/safe drawing insets consistently to top bars, reader content, mini-player, bottom controls, and IME-resized forms.
- Favor a book-centric hierarchy, ample reading line spacing, restrained accent color, and large audio controls. Avoid duplicating Evie's logos, exact layouts, screenshots, or copy.
- Use a persistent, compact player above the bottom safe area rather than a permanent control-heavy toolbar in the text area.
- Reader, playback, and import controls need TalkBack content descriptions, predictable focus order, adequate touch targets, and support for Android font scaling.
- On tablets/foldables, use a wider library/detail layout and avoid stretching line lengths; keep reader and TOC as a two-pane option when practical.

## 9. Key acceptance criteria

1. A user can open an EPUB from the picker, see its title/TOC, start narration, and observe the current utterance while the reader remains synchronized.
2. Closing/reopening the app restores the same publication and locator; removing it from the library does not remove the original source file.
3. A user can select System TTS or Pocket TTS, adjust supported speech settings, and resume with the saved per-book provider when available.
4. Pocket TTS setup is explicit, resumable, checksum-verified, and usable without network after successful model installation.
5. Narration continues with the screen off and is controllable through the notification/lock screen/headset media buttons.
6. A sleep timer stops narration at its expiry and retains the current reading location.
7. A text-based PDF can be navigated in page layout; when extraction is usable, reflow narration follows extracted text. An image-only PDF gives an explanatory unsupported-content message.
8. A registered folder remains in the library after process/app restart; scanning lists supported books without copying them, and removing an entry never deletes the source file.
9. Revoked/missing folder permissions produce a regrant/remove action without discarding saved book progress.
10. Reader controls work at minimum target font scaling and common phone/tablet window sizes without colliding with status/navigation bars or camera cutouts.
11. Most development/regression checks run on a host AVD using Android System TTS. Real Pocket TTS audio/performance checks run on a Pixel 10 with host-provisioned, device-cached model files.

## 10. Open product decisions

- Confirm app store / distribution plans before locking the final license/attribution and model-download hosting strategy.
- Decide how users want to handle duplicate files found in multiple registered folders. The initial design uses user-initiated recursive scan with canonical URI deduplication.
- Decide default playback resume behavior after force-stop/reboot and after wired/Bluetooth device disconnect; follow Android audio-focus/media conventions.
