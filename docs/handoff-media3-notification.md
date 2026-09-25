# Handoff: Media3 media-style notification is not published (no transport controls)

> **Status: RESOLVED** in commit `3eee78b fix: register playback session for Media3 notification`.
> Root cause: the service-owned `MediaSession` was never registered via
> `MediaSessionService.addSession()`, so Media3 never created its internal notification controller
> and never published its `MediaStyle` notification. Fix: call `addSession(session)` when the
> service builds its session. Verified on Pixel 10 (shade + lock screen controls). Retained as the
> original problem description; see `LEARNINGS.md` for the durable lesson.

EpubReader narrates EPUBs through a `MediaSessionService` backed by Readium's TTS Media3 player.
Narration and the `MediaSession` work, but the **notification/lock-screen transport controls never
appear**. Instead of Media3's media-style notification, the system shows the app's hand-posted
foreground notification ("Preparing book playback"). Goal: let Media3 own the notification so
play/pause + previous/next sentence show in the shade and on the lock screen.

## Environment

- Repo: `I:\Android_Projects\EpubReader`, branch `main` @ `997c33f` (clean except untracked
  `gradle/gradle-daemon-jvm.properties`).
- Android app, Kotlin + Compose. Pinned: **Media3 `1.11.1`** (`gradle/libs.versions.toml`),
  **Readium `3.4.0`**.
- Device: Pixel 10, adb id `57220DLCR002R6`, Android 17 / API 37.
- Build: `$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"; ./gradlew.bat :app:assembleDebug`
  (Windows/PowerShell; never Gradle in WSL). Tests: `:app:testDebugUnitTest`.

## Observed behavior (device evidence)

- `dumpsys media_session`: EpubReader session `active=true`, `state=PLAYING(3)`, `actions=4194947`
  (includes `ACTION_PLAY_PAUSE`), metadata `20,000 Leagues Under the Sea, Jules Verne`,
  `controllers: 6`.
- `dumpsys notification --noredact`: notification `id=4102`, `pkg=com.geneing.epubreader`,
  `channel=book_playback`, `flags=ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE`, `category=transport`,
  `android.title=EpubReader`, `android.text=Preparing book playback`. This is the app's manual
  notification, **not** a `MediaStyle` one.
- Before adding `POST_NOTIFICATIONS`, no EpubReader notification appeared at all
  (`numPostedByApp=0`). After declaring + granting it, only the manual "Preparing" notification
  appears. `refreshMediaNotification()` / `triggerNotificationUpdate()` has no visible effect.
- App-process logcat shows no Media3 notification warnings/errors.

## Relevant code — `app/src/main/java/com/geneing/epubreader/playback/BookPlaybackService.kt`

- `onCreate` (L205–220): builds `MediaSession.Builder(this, placeholderPlayer)`, then
  `setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)` and
  `setMediaNotificationProvider(ProgressMediaNotificationProvider(this))`.
- `onStartCommand` `ACTION_START` (L228–242): `if (ttsNavigator == null) showPreparingNotification()`
  before `startPlayback(...)`.
- `startPlayback` (L263–347): after loading the publication,
  `mediaSession?.setPlayer(ProgressPlayer(navigator.asMedia3Player()))` (L326) then `navigator.play()`
  (L333).
- `refreshMediaNotification` (L605–610):
  `session.setMediaButtonPreferences(mediaButtonPreferences()); triggerNotificationUpdate()`.
  Called on playback-state changes (L478) and on every location/utterance change (L494).
- `mediaButtonPreferences` (L618–634): `ICON_SKIP_BACK`→`SLOT_BACK`, `ICON_SKIP_FORWARD`→`SLOT_FORWARD`,
  `ICON_STOP`→`SLOT_OVERFLOW`, each bound to a `SessionCommand`.
- `showPreparingNotification` (L649–666): builds a plain `NotificationCompat` notification (small icon
  `android.R.drawable.ic_media_play`, title app name, text "Preparing book playback", ongoing,
  category transport) and calls
  `ServiceCompat.startForeground(..., NOTIFICATION_ID=4102, ..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`;
  sets `hasPlaybackNotification=true` (L665).
- `ensureNotificationChannel` (L668–679): channel `book_playback`, `IMPORTANCE_LOW`.
- `ProgressPlayer` (L700–705): `ForwardingPlayer` overriding only `getMediaMetadata()` to add
  `"<n>% read"` subtitle.
- `ProgressMediaNotificationProvider` (L707–720): `DefaultMediaNotificationProvider(context,
  { NOTIFICATION_ID }, "book_playback", R.string.playback_notification_channel)` overriding
  `getNotificationContentText`.
- `closeActivePublication` (L567–579): resets `mediaSession.setPlayer(placeholderPlayer)`.
- `stopPlayback` (L554–565): `stopForeground(STOP_FOREGROUND_REMOVE)`, `hasPlaybackNotification=false`,
  `stopSelf()`.
- `mediaSessionCallback.onConnect` (L97–110): accepts all controllers, adds the three custom session
  commands. `onCustomCommand` (L112–125) dispatches skip/stop. `onPlayerCommandRequest` (L128–144)
  handles `COMMAND_PLAY_PAUSE`.
- Constants (L736–737): `NOTIFICATION_ID = 4102`, `NOTIFICATION_CHANNEL_ID = "book_playback"`.

Supporting files: `app/src/main/AndroidManifest.xml` (service + `POST_NOTIFICATIONS`),
`MainActivity.kt` (runtime request), `PlaybackState.kt` (`PlaybackServiceCommands.start` uses
`startForegroundService`).

## Root-cause analysis so far

Media3's `MediaSessionService` publishes its `MediaStyle` notification via
`MediaNotificationManager.updateNotification`, which gates on `shouldShowNotification(session)`.
Disassembly of `androidx.media3.session.MediaNotificationManager` shows it returns `false` when:

1. `getConnectedControllerForSession(session)` is `null` (its internal notification `MediaController`
   future not done), **or**
2. `controller.getCurrentTimeline().isEmpty()` is `true`.

`MediaSessionService.addSession(session)` creates that internal controller (posted to the main
handler); `MediaNotificationManager.addSession` builds it and stores `controllerFuture`.

The Readium player should satisfy (2): `navigator.asMedia3Player()` returns
`org.readium.navigator.media.tts.session.TtsSessionAdapter`, which holds
`mediaItems: List<MediaItem>` and returns a non-empty timeline when `mediaItems` is non-empty
(verified by disassembling `readium-navigator-media-tts-3.4.0`; the factory builds one `MediaItem`
per resource).

So the leading hypothesis is **(1): Media3's internal notification controller is not connected**
(or the session was never registered with the notification manager), leaving `shouldShowNotification`
false — while the app's own `startForeground` notification keeps the FGS requirement satisfied.

> Caveat: the decompile was of the cached `media3-session 1.10.0` classes (the cache also has
> 1.11.0/1.11.1). Re-confirm `shouldShowNotification` / `addSession` against the **resolved 1.11.1**
> artifact before fixing.

## Hypotheses to test (fastest first)

1. **Instrument the gate.** Override `onUpdateNotification(session: MediaSession)` (and/or the
   `(session, startInForegroundRequired)` overload) in `BookPlaybackService` and log:
   `session.player.mediaItemCount`, `session.player.currentTimeline.isEmpty`,
   `session.player.isPlaying`, `session.player.playbackState`, plus whether it is reached at all.
   This discriminates timeline-empty vs controller-not-connected.
2. **Manual `startForeground` may be blocking Media3.** Test removing `showPreparingNotification()`
   and relying on `setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_ALWAYS)` to
   satisfy the `startForegroundService` 5-second window. If the service fails to go foreground in
   time, keep a *brief* placeholder (or `startForeground` with Media3's notification once available)
   rather than an indefinite one.
3. **`ProgressPlayer` wrapper.** Try setting the raw `navigator.asMedia3Player()` as the session
   player (or a `ForwardingPlayer` that does not override metadata) to rule out the wrapper affecting
   timeline/media-item visibility.
4. **Controller connection.** Verify `MediaSessionService.addSession` runs for this session and that
   `onConnect` accepts the internal notification controller (it currently accepts all controllers).
   Check whether the internal `MediaController` future completes.
5. **Timeline fallback.** If the TTS adapter's timeline is empty in practice, explicitly set a
   `MediaItem` (book title/author, `MediaMetadata`) on the session player/wrapper so the timeline is
   non-empty.

## Suggested fix direction

- Let Media3 own the foreground + media notification; stop posting the indefinite placeholder with
  the same notification id.
- Guarantee the session player exposes a non-empty timeline with proper `MediaMetadata` when the
  notification manager evaluates it.
- Keep the existing `mediaButtonPreferences` slots (play/pause is auto-inserted between
  `SLOT_BACK`/`SLOT_FORWARD`; stop stays in overflow).

## Acceptance criteria

- During Pocket/System narration, the notification shade **and** lock screen show play/pause plus
  previous/next sentence, with stop reachable; controls route through the existing session commands.
- Notification reflects title/author + reading progress; no duplicate "Preparing" notification
  remains after playback starts.
- `:app:assembleDebug` and `:app:testDebugUnitTest` pass; `POST_NOTIFICATIONS` flow unchanged.
- Update `Progress.md` / `LEARNINGS.md`, commit on a focused branch, merge to `main`
  (repo convention: fast-forward, linear history).

## Repro / verification commands

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
./gradlew.bat :app:assembleDebug
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell cmd statusbar expand-notifications
& $adb shell dumpsys notification --noredact | Select-String geneing
& $adb shell dumpsys media_session
```

Test flow: launch app → tap the book card (screen ≈ `540 1040`) → tap the "Read aloud" FAB
(≈ `843 2252`) → wait for playback → inspect shade/lock screen.

## Constraints

- Follow `AGENTS.md`: Gradle runs on Windows with `ANDROID_HOME` set; Pixel 10 is for Pocket
  inference; do not commit model weights, books, generated audio, or the untracked
  `gradle/gradle-daemon-jvm.properties`; update the tracking docs in the same checkpoint.
