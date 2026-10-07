# Passenger Remote for Android Auto — Technical Implementation Plan

> Revision 2 (2026-09-21). Changes from revision 1: the remote control now also
> runs **on the driver's own phone**, as a first-class mode rather than a
> convenience. That turns out to reorder the whole build: see
> [Component 3](#component-3--self-remote-the-drivers-own-phone) and the
> [revised phases](#recommendations).

## TL;DR
- **Build it as two ordinary Android apps, not Android Auto apps**: a driver-side "service" app (a NotificationListenerService + foreground service + connection host) and a passenger-side Jetpack Compose controller app. Media control, now-playing, and album art are fully feasible **rootless** via `MediaSessionManager.getActiveSessions()` behind notification-listener access. The **Nearby Connections API (P2P_STAR)** is the best primary transport; a local hotspot/BLE + internet-relay hybrid is the fallback.
- **The remote also runs on the driver's own phone**, over an in-process loopback link — same UI, same host, no pairing and no radio. This covers three real situations: handing the phone to the kids in the back seat, handing it to the front passenger without them needing to install anything, and using it as a big-button remote when parked (doors open at the beach). It ships as one APK that can be host, remote, or both at once.
- **Three of your six required features are hard or impossible rootless**: reliable cross-app *queue* reading fails for the biggest streaming apps (Spotify exposes only the current track via MediaSession); *switching the head-unit foreground app* (map ↔ media) is **not officially possible** — only navigation can be foregrounded indirectly via a `geo:`/`google.navigation:` intent; and *volume control* works only on some cars (a real, documented hardware limitation, not a bug).
- **The prior art (martoreto/AA Passenger + AA 2nd Seat) proves the concept but is effectively abandoned** — its last release was 1.9-alpha9, published to GitHub on Jan 8, 2018 (Play Store listing last updated Sep 19, 2018), and it relied on the *unofficial* Android Auto SDK build. You can do better on the media/remote side with public APIs, but you should NOT try to render your own UI onto the AA projection surface — that path requires the unofficial SDK or root and is exactly why the old project needed a sideloaded AA plugin.

## Key Findings

**Architecture, in one line:** The driver app owns all privileged access (notification listener → media sessions, AudioManager, intents to Maps). The remote app is a thin UI. They talk over a link with a small JSON/protobuf message protocol. The driver app is the security boundary and keeps veto power — **including when both halves are running on the same phone**.

**Feasibility summary (details and code below):**

| Feature | Rootless | Shizuku | Root | Verdict |
|---|---|---|---|---|
| Media transport (play/pause/next/prev/seek) | ✅ NotificationListener + MediaController | — | — | Fully works |
| Now-playing (title/artist/album/art/position/state) | ✅ MediaMetadata + PlaybackState | — | — | Fully works |
| Album art transfer | ✅ downscale + WebP over link | — | — | Fully works |
| **Remote UI on the driver's own phone** | ✅ in-process loopback link | — | — | **Fully works, no transport needed** |
| **Lock the phone to the remote before handing it over** | ✅ `Activity.startLockTask()` screen pinning | — | — | **Works; strength depends on a system setting — see caveats** |
| Now-playing queue read | ⚠️ Only apps that populate `setQueue()` (Poweramp, recent Pocket Casts, local players). Empty for Spotify/YouTube Music/Apple Music | same | same | Partial; per-app fallbacks needed |
| Skip-to-queue-item | ⚠️ Only where a queue exists and IDs are consistent | same | same | Partial |
| Switch head-unit map ↔ media app | ❌ Not possible via public API | ❌ | ⚠️ Nav foregrounding only, via intent; true app-switch needs unofficial AA SDK/hacks | Mostly not possible |
| Send destination to nav | ✅ `geo:` / `google.navigation:` intent | — | — | Works (starts/replaces nav) |
| Trigger voice/Assistant | ⚠️ `ACTION_VOICE_COMMAND` shows AA mic UI but often won't capture; `ACTION_ASSIST` opens full Assistant | ✅ `input keyevent 231` more reliable | ✅ key event | Partial rootless, better with Shizuku |
| Volume control | ⚠️ `AudioManager.setStreamVolume(STREAM_MUSIC)` — works on some cars only | same | same | Hardware-dependent |

## Details

### Component 1 — Driver Service App (the privileged host)

**Package layout (Kotlin), as built:**
```
:protocol   dev.passengerremote.protocol   Messages, RemotePolicy, JsonCodec
:transport  dev.passengerremote.transport  Transport interface, LoopbackLink
:host       dev.passengerremote.host       SessionRepository, CommandExecutor,
                                           VolumeController, DestinationSender,
                                           VoiceTrigger, RemoteHostService
:remote     dev.passengerremote.remote     RemoteClient + Compose RemoteScreen
:app        dev.passengerremote.app        Console, SelfRemoteActivity, one APK
```

The module split is not cosmetic. `:remote` depends on `:protocol` and
`:transport` and **not** on `:host` — the remote UI physically cannot reach a
MediaController. That is what lets the identical screen run against a paired
passenger phone and against the phone it is hosted on, and what makes the
restricted handoff profile enforceable rather than advisory.

**Media control — the core, fully rootless.** With notification-listener access granted, call:
```kotlin
val msm = getSystemService(MediaSessionManager::class.java)
val comp = ComponentName(this, MediaNotificationListener::class.java)
val controllers: List<MediaController> = msm.getActiveSessions(comp) // index 0 = highest priority
val active = controllers.firstOrNull() ?: return
val tc = active.transportControls
tc.play(); tc.pause(); tc.skipToNext(); tc.skipToPrevious(); tc.seekTo(posMs)
```
`getActiveSessions()` normally requires the signature/privileged `MEDIA_CONTENT_CONTROL` permission (reserved for system apps, not obtainable by third parties), **but the documented alternative is to be an enabled NotificationListenerService and pass your listener's ComponentName** — this is the rootless path and is exactly how media-control widgets work. Register `MediaSessionManager.addOnActiveSessionsChangedListener()` to track when the playing app changes, and `MediaController.registerCallback()` for metadata/state updates.

> **Implementation note that only matters once the remote is on the host phone:**
> this app must never publish a MediaSession of its own. On a passenger's phone
> that would be harmless; on the driver's phone it could become index 0 in
> `getActiveSessions()` and the app would end up controlling itself.

**Now-playing info.** From the active controller:
```kotlin
val md: MediaMetadata = active.metadata           // TITLE, ARTIST, ALBUM, DURATION, ALBUM_ART bitmap
val ps: PlaybackState = active.playbackState       // state, position, playbackSpeed, actions
```
Position must be *extrapolated* on the remote side: `estimated = ps.position + (now - ps.lastPositionUpdateTime) * ps.playbackSpeed`. Only send periodic sync (every 2 s playing / 5 s idle), not a per-frame stream — this saves battery and bandwidth. Note that `lastPositionUpdateTime` is sampled against `elapsedRealtime()`, so the remote must extrapolate against the same clock; sending wall-clock time drifts by however long the device has been awake.

**Album art transfer.** `METADATA_KEY_ALBUM_ART` gives a Bitmap (may be null; fall back to `METADATA_KEY_ART` or `METADATA_KEY_DISPLAY_ICON`). Do NOT ship the raw bitmap. Downscale to ~384 px, compress to WebP (lossy ~80%), hash it, and send with a content hash so the remote caches and you only transfer on track change. Typical payload drops to ~10–30 KB.
**Over the loopback link this step is skipped entirely** — both ends share a heap, so the Bitmap is passed by reference. `Artwork` carries either `bytes` or `bitmap`, the encoder runs only if at least one attached remote is non-loopback, and the UI takes whichever it is handed.

**Queue (the honest verdict).** `MediaController.getQueue()` returns `List<MediaSession.QueueItem>` and `transportControls.skipToQueueItem(id)` jumps to one — **but only if the source app called `setQueue()`.** Per-app reality (validate in Phase 0):
- **Spotify: no.** MediaSession exposes only the current track. The up-next queue is reachable only via the Spotify Web API `GET /v1/me/player/queue` (OAuth-scoped, flaky, and Spotify's terms bar commercial streaming integrations). Add-only writes go through the App Remote SDK `PlayerApi.queue(uri)`.
- **YouTube Music: unreliable/no** — partial session integration, long-standing "view queue" gaps in Auto.
- **Apple Music (Android): no** via standard MediaSession (proprietary MusicKit `MediaPlayerController`).
- **Poweramp (v3 build 817+): yes**, full queue + `skipToQueueItem`.
- **Pocket Casts: now yes** (2025 Media3 change added `setQueue` + `onSeekToQueueItem`); older versions no.
- **Pandora, Amazon Music, Audible: unconfirmed, assume empty.**
- **Local file players: usually yes.**

Design accordingly: probe `getQueue()?.isNotEmpty()` *and* the `ACTION_SKIP_TO_QUEUE_ITEM` bit; if either is missing, show only the current track and say which app is withholding the queue. `skipToQueueItem` is documented as "undefined" for bad IDs and has known shuffle-index bugs, so validate the id against the live queue before sending it.

**Switching map ↔ media on the head unit (feature 5 — be honest: mostly not possible).** There is **no public API** to tell the Android Auto projection UI "bring the media app (or the map) to the foreground." What *is* possible:
- **Foreground navigation** by firing a navigation intent from the driver phone: `Intent(ACTION_VIEW, Uri.parse("google.navigation:q=..."))` with `setPackage("com.google.android.apps.maps")`, or the Automotive `geo:0,0?q=...` intents. When AA is connected, sending a destination brings Maps/nav to the foreground on the head unit. Waze deep links only work when Waze is already running. `am start` with the `androidx.car.app.category.NAVIGATION` category does **not** work.
- **You cannot reliably switch *to* the media app** or emulate the AA launcher. Apps that appear *inside* AA are themselves CarAppService/AA media apps that were sideloaded and whitelisted. None is a supported API.
- **Best workaround:** ship a nav button (works) and a "resume media" button that calls `transportControls.play()`. Document clearly that arbitrary screen-switching on the head unit is a Google-controlled UX you cannot drive programmatically.

**Voice/Assistant button (feature 6).** Ranked: `ACTION_VOICE_COMMAND` (closest to the AA mic UI, but often shows listening without capturing) → `ACTION_ASSIST` (reliable, but full-screen) → Shizuku/root `input keyevent 231` (`KEYCODE_VOICE_ASSIST`, "not delivered to applications", so dispatch-only via shell). Ship the first two rootless and offer the third as a Shizuku "reliable mode."

**Volume control (feature 2 — with the required disclaimer).**
```kotlin
val am = getSystemService(AudioManager::class.java)
if (am.isVolumeFixed) { /* disable slider, show disclaimer */ }
else am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
```
**Why it only works on some cars:** in wired AA the phone's `STREAM_MUSIC` level is often what is sent, so `setStreamVolume` works. But many head units control their own amplifier independently; there the device behaves as a fixed-volume sink and `setStreamVolume()` "has no effect if the device implements a fixed volume policy as indicated by `isVolumeFixed()`." Over Bluetooth, Absolute Volume merges the two scales. Expose the control, detect `isVolumeFixed()`, show the disclaimer.

> **Volume disclaimer text (ship this verbatim):** *"Volume control works on some vehicles only. Many car head units manage their own amplifier and volume independently of your phone, so this slider may have no audible effect in your car. It is most likely to work with wired Android Auto and least likely to work when your car controls volume from its own dial or over Bluetooth. This is a limitation of your vehicle, not a bug in the app."*

### Component 2 — Passenger Controller App (Jetpack Compose)
Thin client: now-playing (with extrapolated seek bar), transport buttons, volume slider (greyed when the host reports fixed volume), queue list (when available), destination search box, voice button, and the song-request/suggestion queue. All actions are *requests*; the host validates against the sender's policy and executes.

### Component 3 — Self-Remote (the driver's own phone)

**The requirement.** The remote must run on the driver's phone too, so the driver can:
1. hand their own phone to the kids in the back seat,
2. hand it to the front passenger, who then needs nothing installed,
3. use it themselves as a big-button remote when parked — doors open at the beach, phone in hand, radio playing through the car.

**How it works.** The host service and the remote UI already talk only in
protocol messages. Self-remote is therefore a *transport*, not a second app:

```
RemoteHostService ──► LoopbackLink.hostEnd   ┐
                                             │  two in-process SharedFlows
SelfRemoteActivity ──► LoopbackLink.remoteEnd ┘
```

Nothing is paired, serialised, encrypted or radioed. `Transport.isLoopback` is
the only thing above the transport layer that ever checks which kind of link it
is on, and it checks for exactly two reasons: skip pairing UI, and skip artwork
compression.

Two asymmetries in the loopback link are deliberate and worth keeping:
- **host → remote replays** (replay = 8). The remote is an Activity and
  resubscribes on every rotation and every return from background; without a
  replay the screen would come back blank until the next metadata change, which
  for a paused track may be never.
- **remote → host does not replay.** A replayed `Command` would be re-executed
  every time the service resubscribed — the phone would skip tracks on its own.
  There is a regression test for this.

**Three roles, one screen.** `RemoteRole` is `SELF`, `HANDOFF` or `PASSENGER`,
and each maps to a `RemotePolicy`:

| | `OWNER` (SELF) | `BACK_SEAT` (HANDOFF) | `PASSENGER` |
|---|---|---|---|
| play/pause/next/prev | ✅ | ✅ | ✅ |
| seek | ✅ | ❌ | ✅ |
| volume | ✅ | ✅, capped | ✅ |
| jump to queue item | ✅ | ❌ | ✅ |
| send destination | ✅ | ❌ | driver approves |
| voice | ✅ | ❌ | ❌ |
| skip rate limit | none | 6/min | 20/min |

Policy is enforced in `CommandExecutor` on the **host** side of the link, which
is the whole reason handoff means anything: the UI the kid is holding is not the
thing deciding what the kid may do.

**Handoff mode specifics.**
- `Activity.startLockTask()` pins the remote to the screen. On an unmanaged
  device this is ordinary screen pinning: the system shows a confirmation the
  first time, the status bar and recents are hidden, and leaving requires the
  unpin gesture (plus the device PIN if the user has enabled "Ask for PIN before
  unpinning"). No device-owner setup, no root.
- Back is swallowed (`BackHandler`), the activity is `excludeFromRecents` and
  `singleTask`, and returning the phone needs a deliberate press-and-hold.
- The volume ceiling is computed *at handoff time* from wherever the driver left
  the volume, plus a small headroom, so handing the phone back cannot turn into
  a jump scare.
- `FLAG_KEEP_SCREEN_ON` — a remote nobody can see is useless.

**Honest limits of handoff mode** (these belong in the UI, not just here):
- Screen pinning is a *convenience barrier*, not a security boundary. On most
  OEMs it must be enabled in Settings, and without "ask for PIN before
  unpinning" a determined passenger can long-press back+overview and be out.
- Incoming calls still take the screen.
- Handing someone an unlocked phone is a trust decision that no app removes.

**Self-remote while Android Auto is projecting — the one real unknown.** Modern
AA leaves the phone screen usable, so opening the remote on the driver's phone
mid-drive should just work — but this is version- and car-dependent and is a
Phase 0 item, not an assumption. If some head units do lock the phone screen,
self-remote still fully covers the parked cases (beach, handing it to the back
seat at a stop) and only the drive-time case regresses.

**Distraction.** Self-remote is aimed at parked and stopped use. The app is not
an Android Auto app, so Google's driver-distraction rules do not formally apply,
but the intent should be stated in the UI. A GPS-speed gate that collapses the
screen to four huge buttons above ~5 mph is a cheap Phase 2 addition.

### Graceful degradation and optional elevated access

**Principle: every feature does as much as it can with no special setup, says
plainly what it cannot do, and only then offers a way to do better.** Nothing is
gated behind Shizuku or root. The app is fully usable at `AccessTier.ROOTLESS`
and that is the tier it is designed and tested for.

`CapabilityReporter` measures, rather than claims, what works — against live
state, because the answers differ per phone, per car and per playing app:

| Feature | Rootless | What extra privilege changes |
|---|---|---|
| Play/pause/skip, now-playing, artwork | Works | Nothing — already complete |
| Seek | Follows the app's advertised actions | Nothing |
| Queue | Works only where the app calls `setQueue()` | **Nothing.** The queue is absent from the media session itself; no privilege on this phone conjures data the source app never published |
| Volume | Works unless `isVolumeFixed()` | **Nothing.** A head unit running its own amplifier ignores the phone at every tier |
| Send destination | Works | Nothing |
| Voice button | Limited — `ACTION_VOICE_COMMAND` often shows the listening bar without capturing | **Partly.** Shell access dispatches `KEYCODE_VOICE_ASSIST` (231) instead of an ignorable intent. **Measured 2026-09-21: it still opens the assistant on the *phone*, not the AA mic** — the common claim that 231 "routes to the vehicle assistant" is false |
| Switch apps on the car screen | Blocked | **Nothing.** There is no API at any tier; the only routes are the unofficial AA SDK or screen mirroring, and this app does neither |

The second column is the honest part and is enforced by tests: `improvedBy` is
null for volume, queue, and head-unit app switching, so the UI cannot advertise
Shizuku as a fix for any of them. Offering a general "unlock more features"
button would be selling three fixes that do not exist.

**How the elevation works.** `KEYCODE_VOICE_ASSIST` is documented in AOSP as
"not delivered to applications" — it can only ever be *dispatched* from a shell,
never received, which is precisely why no rootless implementation exists. The
app therefore runs a **Shizuku UserService** (`IElevatedService`): Shizuku's own
`newProcess()` is private in api 13.x, and reflecting into it fights the
library, whereas a user service is the supported mechanism and generalises to
any later elevated operation. Root is supported by the same interface via `su`,
so nothing above `ElevatedAccess` knows or cares which route was taken.

Shizuku's service stops on every reboot — a Shizuku limitation, not this app's —
so `ShizukuState` distinguishes not-installed / not-running / needs-permission /
too-old / ready and the UI says which one applies instead of failing silently.

### Message protocol (JSON over radio transports; objects passed as-is over loopback)
```jsonc
// host -> remote
{ "t": "nowplaying", "trackId": "abc", "title": "...", "artist": "...", "album": "...",
  "durationMs": 214000, "positionMs": 51000, "posUpdatedAt": 51234567,
  "status": "PLAYING", "speed": 1.0, "artHash": "sha1:...", "pkg": "...", "source": "Spotify",
  "caps": { "seek": true, "next": true, "prev": true, "queue": false } }
{ "t": "artwork", "trackId": "abc", "hash": "sha1:...", "mime": "image/webp", "b64": "..." }
{ "t": "queue", "items": [ { "qid": 12, "title": "...", "artist": "..." } ], "activeQid": 12 }
{ "t": "volume", "index": 8, "max": 25, "fixed": false }
{ "t": "policy", "perms": { "playback": true, "seek": false, "volume": true,
  "maxVolIndex": 11, "queueJump": false, "destinations": false, "voice": false,
  "maxSkipsPerMin": 6 } }
{ "t": "cmdResult", "action": "SEEK", "accepted": false, "reason": "Not allowed for this remote" }
{ "t": "sugResult", "id": "r1", "approved": true }
{ "t": "notice", "text": "...", "level": "INFO" }
{ "t": "idle", "reason": "Nothing is playing" }

// remote -> host
{ "t": "hello", "role": "SELF|HANDOFF|PASSENGER", "name": "This phone", "v": 1 }
{ "t": "cmd", "action": "PLAY|PAUSE|PLAY_PAUSE|NEXT|PREVIOUS|SEEK|SET_VOLUME|VOLUME_UP|VOLUME_DOWN|SKIP_TO_QUEUE_ITEM|VOICE",
  "posMs": 60000, "volIndex": 8, "qid": 12 }
{ "t": "sug", "id": "r1", "kind": "SONG|DESTINATION", "query": "...", "from": "Sam" }
```
`policy` replaces revision 1's `auth`: with loopback there is nothing to
authenticate, but there is still very much something to authorise, and both
kinds of remote need the same permission push.

### Connectivity — the transport decision

| Transport | Throughput | Range | Conflicts with wireless AA? | Verdict |
|---|---|---|---|---|
| **Loopback (in-process)** | Unlimited | Same phone | **None** | **Primary for self/handoff — no radio at all** |
| **Nearby Connections (P2P_STAR)** | High (auto Wi-Fi) | ~100 m | Some (uses Wi-Fi/BT underneath) | Primary for a second device |
| BLE GATT | Very low (~kbps) | ~10 m | Low | Control-only fallback; too slow for album art |
| BT Classic RFCOMM | Moderate | ~10 m | Medium | Legacy fallback |
| Wi-Fi Direct | High | Long | **High** — wireless AA already uses 5 GHz Wi-Fi Direct | Avoid as primary |
| Local hotspot | High | Room | **High if AA is wireless** | Fallback for **wired** AA |
| Internet relay (Firebase/FCM/WebRTC) | High | Any | None | Fallback when local radios are saturated |

**The wireless-AA conflict is the crux for the second-device case.** Wireless AA
consumes the phone's Bluetooth *and* a 5 GHz Wi-Fi Direct link; turning on a
hotspot is reported to disconnect it. So: wired AA → Nearby Connections works
well; wireless AA → prefer Nearby (it negotiates BT/BLE when Wi-Fi is busy) or
fall back to BLE for control plus an internet relay for artwork.
**Self-remote sidesteps this entirely** — which is a large part of why it now
ships first.

### Pairing & security
- **Loopback needs no pairing** and adds no attack surface: no discovery, no
  advertising, no key exchange, nothing on the air.
- **QR-code pairing** for real devices: the host shows an endpoint id plus a
  short-lived shared secret; the remote scans it; derive a session key and layer
  app-level auth on top of Nearby's own encryption.
- **The host is the only enforcement point**, for every remote including the
  local one. Per-remote toggles, a driver console listing attached remotes, and
  instant kick/mute.
- **Song requests default to "suggest, driver approves."** Destination requests
  auto-approve only when policy allows it (which `OWNER` does and `BACK_SEAT`
  does not).
- **Multiple passengers:** P2P_STAR is one advertiser + many discoverers.
  Bluetooth tops out around 4 concurrent connections, Wi-Fi at 10–100.

### Background execution (Android 14/15/16)
The host is a foreground service typed `connectedDevice|mediaPlayback`:
> **Corrected after hardware testing (2026-09-21).** Revision 2 originally declared
> `connectedDevice|mediaPlayback` on the theory that `mediaPlayback` "covers" the
> self-remote case. It does not: **the system validates every declared type, not
> whichever one fits**, and `connectedDevice` additionally requires one of
> `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` /
> `CHANGE_NETWORK_STATE` / `CHANGE_WIFI_STATE` / `NEARBY_WIFI_DEVICES` / NFC / UWB /
> USB. With none of those held, `startForeground()` throws `SecurityException` and
> the app crash-loops on launch. Phase 1 touches no radio, so there is no honest
> permission to claim.

Phase 1 therefore declares **`mediaPlayback` only**:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
<service android:name=".service.RemoteHostService"
    android:foregroundServiceType="mediaPlayback" android:exported="false"/>
```
`connectedDevice` gets added back in Phase 2, together with the Nearby Connections
permissions that make it true — at which point the type should be chosen at runtime
via `startForeground(id, notification, type)` so a loopback-only session never claims
a connected device it does not have. Both types may run indefinitely. Android 13+
needs runtime `POST_NOTIFICATIONS`; Android 14+ bans starting these types from
`BOOT_COMPLETED`, so start from user action.

### Play Store policy
- **NotificationListenerService** is a restricted permission needing a Play Console
  declaration; the only durable justification is the device-companion/media-controls
  use case. Frame the app as a media remote, disclose in-app, ship a privacy policy,
  and never read or transmit notification content beyond media sessions. The listener
  here deliberately does not override `onNotificationPosted`.
- **Avoid AccessibilityService.** Screen pinning via `startLockTask()` is a public API
  and needs no accessibility or device-owner privileges — worth stating in the review
  notes, since "locks the phone to one screen" otherwise sounds like it would.
- **Driver distraction rules** apply to apps that project onto the car screen. This app
  has no CarAppService, so it is not subject to AA app review. Say "for passenger use"
  in the listing, and say plainly that the driver-phone remote is for parked or stopped
  use.
- **Sideloaded-AA-plugin apps get pulled.** Not rendering into the AA UI sidesteps that
  entire category.

## Recommendations

**Revised phase order.** Self-remote removes the transport, pairing and second-device
work from the critical path, so the first shippable, testable thing no longer depends
on any of it.

**Phase 0 — Feasibility spike (1–2 weeks).** Build the NotificationListener +
`getActiveSessions()` + transport controls and verify against Spotify, YouTube Music,
Poweramp, Pocket Casts, and a local player. Log which apps return a non-empty
`getQueue()` and whether `isVolumeFixed()` is true in the car. **Added:** confirm the
phone screen is usable and `SelfRemoteActivity` can be foregrounded while AA is
projecting, and that `startLockTask()` pins cleanly on the target phone.

**Phase 1 — Self-remote MVP (no radios, no pairing, ships to Play).** Code complete
as of 2026-09-21; launches clean on the test phone, media path still unverified.
- ✅ Host service + notification-listener onboarding.
- ✅ Loopback link, `RemoteClient`, `RemoteScreen`.
- ✅ Driver-phone remote (`OWNER`) and handoff mode (`BACK_SEAT` + screen pinning).
- ✅ Volume slider with `isVolumeFixed()` detection and the verbatim disclaimer.
- ✅ Queue where the source app publishes one; honest message where it doesn't.
- ✅ Destination box and voice button, policy-gated so they vanish under handoff.
- ✅ Handoff setup: the driver sets the volume headroom, skip rate and what else
  the back seat may touch, persisted between handoffs. The volume ceiling is
  derived at handoff time from the live volume, never stored — a remembered index
  would mean nothing the next time the car is louder or quieter.
- ⬜ Privacy policy (needed before any Play submission, not before testing).

**Phase 2 — Second device.**
- Nearby Connections transport + QR pairing + driver approval console.
- `JsonCodec` + WebP artwork over the wire (both already written, unused until now).
- Passenger "suggest a destination" → driver-approved `geo:` intent.
- Passenger song-request queue with approve/deny.

**Phase 3 — v1 extras.**
- Voice button: `ACTION_VOICE_COMMAND` rootless + optional Shizuku `input keyevent 231`.
- BLE-control + internet-relay hybrid for wireless-AA cars.
- Optional Spotify App Remote integration for real queue add.
- GPS-speed gate for the driver-phone remote.
- Sleep timer; lyrics; NFC tag onboarding; shared collaborative queue.
- Casting a passenger's own audio through the car (needs MediaProjection, or root on
  the passenger device for full system capture — partially feasible only).

**Benchmarks that change the plan:** if `getQueue()` is empty for the target apps,
deprioritise queue features. If `isVolumeFixed()` is true in most testers' cars, move
volume to a labelled beta. If wireless AA breaks Nearby in real cars, promote the
BLE+relay hybrid — and note that self-remote is unaffected either way.

## Caveats
- **Prior art is stale:** martoreto/aapassenger last shipped 1.9-alpha9 in Jan 2018 and
  relied on the unofficial AA SDK. Inspiration, not a dependency.
- **The head-unit foreground app cannot be switched programmatically**; only navigation
  can be foregrounded via intent.
- **Queue and volume are inherently per-app / per-car** and will produce inconsistent
  experiences; lead with disclaimers.
- **Notification-listener access is a Play review risk**; the justification must be the
  media-controller use case.
- **Voice triggering is unreliable rootless.**
- **Screen pinning is a barrier, not a boundary** — see Component 3.
- **Self-remote during active projection is unverified** on real hardware and is a
  Phase 0 item.
- **Google is replacing Assistant with Gemini in Android Auto**; keep voice loosely
  coupled and re-test.
- Per-car volume behaviour, which head units re-focus media on `play()`, and whether
  Nearby survives wireless AA are **device-specific** and must be validated on real
  hardware.
