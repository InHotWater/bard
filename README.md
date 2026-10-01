# Bard

  Bard is a minimalist, text-only audiobook player designed for the Light Phone III. It brings local audiobooks,
  optional Libby loans, and optional RSS audiobooks into one calm Books screen and one consistent player
  interface.

  Bard is currently alpha software. The current application version is 0.1.0-alpha5 (versionCode 5).

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/books.jpg" width="300" alt="Unified Books screen"><br><strong>Books</strong></td>
    <td align="center"><img src="docs/screenshots/player.jpg" width="300" alt="Audiobook Player screen"><br><strong>Player</strong></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/settings.jpg" width="300" alt="Bard Settings screen"><br><strong>Settings</strong></td>
    <td align="center"><img src="docs/screenshots/downloads.jpg" width="300" alt="Downloaded audiobooks screen"><br><strong>Downloads</strong></td>
  </tr>
</table>

---

## Features

  ### Local audiobooks

  - Single-file MP3 and M4B audiobooks placed directly in the shared `Audiobooks` folder.
  - Multi-file audiobooks organized as folders inside `Audiobooks`.
  - Parts play continuously in alphabetical order and share one combined timeline and saved position.
  - Nested audio files within a book folder are included and ordered by relative path.
  - Local books remain in shared device storage; Bard does not copy them into app-private storage.

  ### Libby loans

  - Optional connection using Libby's eight-digit Copy To Another Device setup code.
  - Native, text-only shelf and player presentation; the Libby website remains hidden during normal use.
  - Loan progress and due-date metadata when supplied by Libby.
  - Play/pause, ±15 seconds, absolute seeking, and playback speed through Libby's own player controls.
  - Background playback supported by a media-playback foreground service while a Libby book is actively playing.
  - Bluetooth, car, and LightOS media controls through Bard's native Android media session.

  Bard keeps one application-scoped WebView for the Libby session and player. It uses document-start JavaScript
  injection, a WebMessage bridge, and Libby's own BIF.objects.spool controls. Bard does not extract protected
  media URLs or replace Libby's playback engine.

  ### RSS audiobooks

  - One RSS item treated as one audiobook.
  - MP3 and M4B audio enclosures.
  - Multiple saved HTTP or HTTPS feeds.
  - Streaming through Android's native media playback APIs.
  - User-initiated full-file downloads for offline playback.
  - Downloaded files stored durably in Bard's app-private storage.
  - Manual feed refresh and removal.

  RSS downloads are never automatic. Removing an RSS download keeps the feed item and listening progress, making
  the book streamable again. Removing a feed removes its books from the library.

  ## Library and Playback

  All enabled sources appear together in one Books screen with no source tabs, cover art, or cards. Books are
  ordered by most recently played; books that have never been played follow alphabetically.

  The same player interface is used for every source and provides:

  - Play and pause.
  - Rewind and forward 15 seconds.
  - A scrubbable progress line.
  - Playback speeds of 1×, 1.25×, 1.5×, 1.75×, and 2×.
  - Persisted position, duration, speed, and last-played ordering.
  - Now Playing restoration after app or phone restart without autoplay.
  - Bluetooth and LightOS play, pause, seek, and transport controls.
  - Lock screen / notification now-playing controls: play/pause and back/forward 15 seconds, with title,
    author, and cover art (lock screen only; Bard's own UI still shows no covers). Previous/next on a lock
    screen or headset skip 15 seconds instead of restarting the book.
  - LightOS lock screen controls (Light Phone III). LightOS only draws its own Music/Podcasts player on its
    lock screen and ignores other apps' media sessions and notifications, so Bard draws a small
    title + back 15 / play-pause / forward 15 row over the LightOS lock screen itself. It needs two
    one-time adb grants (LightOS has no Settings screen for them):

    ```bash
    adb shell appops set com.stan.libbylight SYSTEM_ALERT_WINDOW allow
    adb shell appops set com.stan.libbylight GET_USAGE_STATS allow
    ```

    Usage access is how Bard checks that the LightOS lock screen is in front, so the row never covers
    another app. Tap anywhere else (or long-press the row) to hide it until the screen next turns on.
    Settings > Lock Screen shows the status and turns it off.

  The interface is source-independent, but playback is not one single engine: Libby uses its persistent hidden
  WebView player, while local and RSS books use Android's native media player.

  ## Downloads

  The Downloads screen lists only media Bard can accurately identify as stored on the phone:

  - Local MP3/M4B files in the shared Audiobooks folder.
  - Completed Bard-managed RSS downloads.

  Libby loans are deliberately excluded because Bard does not manage or expose Libby's internal offline files.

  Removing a local book from Downloads deletes the original file after Android's confirmation. Removing an RSS
  download deletes only Bard's private downloaded copy and preserves feed metadata, listening progress, speed,
  and ordering.

  ## Getting Started

  ### Local books

  1. Connect the Light Phone III to a computer.
  2. Create an Audiobooks folder at the top level of shared device storage if it does not already exist.
  3. Place a single `.mp3`/`.m4b` file directly in that folder, or add one folder per multi-file audiobook.
  4. For a multi-file book, name its parts in playback order (for example `01`, `02`, `03`).
  5. In Bard, open Settings → Local Books → Scan for Books.

  Android may ask Bard for audio-library permission so it can discover these shared files. Bard does not request
  broad all-files storage access.

  ### Libby

  1. Open Settings → Libby → Connect to Libby.
  2. On another device already connected to Libby, open Menu → Copy To Another Device.
  3. Enter the eight-digit code displayed by Bard.
  4. Bard returns to Books after the Libby session and shelf are ready.

  Newly borrowed Libby books appear without reconnecting: Bard re-syncs the Libby shelf when you return to it
  after a couple of minutes, and Settings → Libby → Refresh Loans re-syncs on demand (pause a playing Libby
  book first; the same hidden Libby page hosts its player).

  Bard's native connection flow does not expose passkey recovery or library-card sign-in. Disconnect clears only
  Bard's local Libby WebView session and does not disconnect other devices.

  ### RSS

  1. Open Settings → RSS Feeds → Add Feed.
  2. Enter an HTTP or HTTPS RSS feed URL using the Light-style keyboard.
  3. Open a book to stream it, or use Download in the player to keep it offline.

  Private feed URLs are stored in app-private preferences and are not written to logs. Username/password
  authentication is not supported.

  ## Permissions and Notifications

  Bard's installed APK uses:

  - Internet and network-state access for Libby and RSS.
  - Audio-library read access for the fixed shared Audiobooks folder.
  - Media-playback foreground-service access for reliable screen-off playback.
  - Vibration support supplied by the embedded Light keyboard component.

  While audio is actively playing, Android shows a private ongoing Bard media notification. For Libby, this also
  keeps Bard's hidden WebView process active when the screen is off. When paused, Bard releases foreground-service
  status but retains a dismissible media notification so LightOS and Bluetooth resume controls remain available.
  Stopping playback removes the notification.

  ## Current Limitations

  - Light Phone III / Android 13 or newer is the current target environment.
  - Multi-file local books use filename/path order; there is no chapter navigation or chapter metadata UI.
  - No chapter navigation or embedded-chapter UI.
  - Bard's UI never displays cover art.
  - RSS expects one supported audio enclosure per item.
  - No authenticated RSS feeds, scheduled refresh, RSS notifications, or automatic downloads.
  - RSS playback is streaming unless the user explicitly downloads the book.
  - Libby requires another device with an authorized Libby session for setup.
  - Libby integration depends on Libby's web application and may require maintenance when that application
    changes.

  - No ebook reading, cloud sync, metadata editing, or podcast-specific episode features.

  ## Architecture

  Bard is a single-module Kotlin Android application built with Jetpack Compose. The current implementation is
  intentionally incremental:

  - Source-neutral Audiobook and progress records feed the unified library.
  - LocalBookRepository discovers fixed-folder local media.
  - RssFeedRepository persists, fetches, securely parses, and caches RSS feeds.
  - RssDownloadManager owns durable RSS offline files and download state.
  - LocalPlaybackController handles local files and RSS streams/downloads.
  - BardMediaSessionManager publishes one Android media session and routes system controls to the active source.
  - LibbyWebPlayer owns the persistent application-scoped WebView.
  - LibbyBridge translates source-neutral player actions to Libby's player.
  - AudiobookProgressStore persists playback state, last-active identity, and recent-first ordering across
    sources.

  The UI is built from Bard's Light-style Compose primitives and selected Light SDK-derived resources. It uses
  the Light LP3 keyboard component for RSS URL entry.

  ## Development

  Requirements:

  - JDK 17.
  - Android SDK with API 36 available.
  - Android device or emulator running API 33 or newer.

  Build the debug APK:

  ./gradlew assembleDebug

  The output is:

  app/build/outputs/apk/debug/app-debug.apk

  Private release signing, verification, installation, and rollback instructions are documented in RELEASE.md
  (RELEASE.md). Signing keys and keystore.properties must remain outside Git.

  ## Privacy and Security

  Bard does not log setup codes, cookies, authentication tokens, signed media URLs, private RSS URLs, local file
  paths, raw browser storage, or raw Libby bridge payloads. WebView debugging is enabled only in debuggable
  builds.

  RSS XML parsing rejects document declarations and does not execute feed HTML or JavaScript. RSS downloads are
  stored in Bard's private files directory. Android backup is disabled for the application.

  ## Important

  Bard is an independent, unofficial open-source project.

  Bard is not affiliated with, endorsed by, sponsored by, or approved by Light, OverDrive, or Libby.

  Bard does not include or redistribute the Libby Android application, Libby source code, audiobook content, DRM-
  protected media, or user credentials.

  When using library loans, Bard relies on the user's own authorized Libby account and the official Libby web
  service. Bard does not provide access to books that a user has not legitimately borrowed.

  Bard does not bypass or remove DRM, proxy audiobook content, or host copyrighted library materials.

  Users are responsible for ensuring that their use of Bard complies with the terms and conditions governing any
  third-party services they choose to access through the application.

  "Libby" and "OverDrive" are trademarks of OverDrive, Inc. "Light" and "Light Phone" are trademarks of their
  respective owners. All trademarks are used solely for identification and compatibility purposes and remain the
  property of their respective owners.

  ## License and Third-Party Components

  Original source code written specifically for Bard is available under the MIT License (LICENSE).

  Bard includes Light SDK-derived user-interface components and resources. Their copyright notices and license
  terms are reproduced in THIRD_PARTY_NOTICES.md (THIRD_PARTY_NOTICES.md). Third-party components remain subject
  to their respective licenses.
