# Passenger Remote

A rootless media remote for cars running Android Auto. One APK that can be the
**host** (the phone that is actually playing and projecting), the **remote**, or
both at the same time.

The full design, feasibility notes and phase plan are in
[`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md).

## The three ways to use it

| | Who holds the remote | Link |
|---|---|---|
| **Remote** | the driver, on their own phone | in-process loopback |
| **Hand it over** | whoever the driver passes the phone to | in-process loopback, screen-pinned, restricted profile |
| **Passenger phone** *(Phase 2)* | a second device | Nearby Connections |

The remote UI is the same screen in all three. It speaks only protocol messages
and has no access to media APIs — the host enforces what each remote may do, so
"hand the phone to the kids" is a real restriction and not a polite suggestion.

## Modules

```
:protocol   messages, RemotePolicy, JSON codec        (no Android APIs beyond org.json)
:transport  Transport interface + LoopbackLink
:host       media sessions, volume, nav, voice, foreground service
:remote     RemoteClient + Compose RemoteScreen        (cannot reach :host)
:app        driver console, self-remote activity, one APK
```

## Status

Phase 1 verified **in a car, with Android Auto projecting**, on a Motorola Edge
2025 against YouTube Music. Builds clean, 28 unit tests pass.

| Function | Result |
|---|---|
| Reads now-playing while AA projects | ✅ live title/artist, updates on track change |
| Play / pause | ✅ session goes PLAYING ↔ PAUSED |
| Next / previous | ✅ track changes and returns |
| Seek | ✅ scrubbed to 3:04 of a 4:01 track |
| Volume | ✅ 2 → 5 → 2 (see caveat below) |
| Album art | ✅ renders over the loopback link |
| Queue | ✅ correctly reported absent for YouTube Music |
| Handoff / screen pinning | ✅ pins; restricted profile hides destination + voice and makes the seek bar read-only |
| Destination | ✅ confirmed working by the owner |
| Shizuku elevation | ✅ UserService binds; reports `Connected. Commands run as root / 0` |
| Voice (elevated) | ⚠️ works, but **does not reach the Android Auto mic** — see below |

**Volume caveat:** during this test `STREAM_MUSIC` was routed to `speaker(2)` —
the phone's own speaker, not the car. So this proves the slider moves the
phone's media volume; it does **not** prove anything about a car that owns its
own amplifier. `isVolumeFixed()` was false here. The disclaimer stands.

### Known hardware findings

**`foregroundServiceType` must be `mediaPlayback` only** until Phase 2. Android
validates every declared type, so adding `connectedDevice` without one of the
Bluetooth/Wi-Fi permissions makes `startForeground()` throw and the app
crash-loop on launch. See the comment in `host/src/main/AndroidManifest.xml`.

**Every screen must sit inside a `Surface`.** Outside one, Material3 leaves
`LocalContentColor` at black, so text on these dark screens renders
black-on-black. `RemoteTheme` now provides the Surface (and the
`safeDrawingPadding` that keeps content out from under the status bar, which
targetSdk 35+ requires whether you opt into edge-to-edge or not).

**The phone screen stays usable while Android Auto projects.** This was the
plan's biggest open risk for the self-remote: the app foregrounds, reads the
session and drives playback normally with AA connected and projecting.

**`startLockTask()` works even when `lock_to_app_enabled` reads null.** That
setting being unset does not mean pinning is unavailable; the system simply
shows its "App is pinned" consent dialog on first use. Don't gate the handoff
feature on reading that setting.

**singleTask activities must override `onNewIntent`.** `SelfRemoteActivity` is
singleTask, so "hand it over" while the remote is already open re-delivers
rather than recreating. Reading `intent` only in `onCreate` meant the restricted
profile silently never applied in exactly that case. Caught on hardware, fixed
in 0.2.1.

**`KEYCODE_VOICE_ASSIST` does NOT reach the Android Auto mic.** A widely-repeated
claim is that `input keyevent 231` "routes directly to the active vehicle
assistant". Tested with AA connected and projecting, it opens
`googlequicksearchbox/...FloatyActivity` — the assistant overlay **on the
phone** — exactly like the rootless intent path does. Shizuku may still make the
assistant actually start listening rather than just showing a bar, but it does
not put the mic on the car screen, and nothing can. The in-app copy says so.

**Nearby caps a BYTES payload at 32 KB, and album art is the message that
straddles it.** Art is downscaled and WebP'd to ~10-30 KB, which base64 inflates
by a third — so sending messages whole would have failed on *some* album covers
and worked on others. Every message is framed and chunked uniformly instead;
`PayloadFramingTest` covers reassembly, out-of-order arrival, interleaving and
eviction of abandoned partials.

**YouTube Music confirms the plan's queue prediction:** its session exposes
`activeQueueItemId` but its action mask omits `ACTION_SKIP_TO_QUEUE_ITEM`, so
`hasQueue` correctly reports false and the UI says so rather than offering dead
buttons.

## Building

```bash
export JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
```

`local.properties` points at the shared SDK in `AAMedia/toolchain/sdk`
(platform 37, AGP 9.4.0, Kotlin 2.4.20, Gradle 9.7.1). This host has no working
IPv6 route, so `gradle.properties` forces IPv4 — see the comment there.

## On-device setup

1. Open the app, grant **notification access** when prompted. This is the only
   rootless way a normal app may call `MediaSessionManager.getActiveSessions()`;
   the listener here never reads notification content.
2. Start something playing in any music app.
3. **Open remote** for the driver's own remote, or **Hand it over** for the
   back-seat profile.

For handoff to be worth anything, turn on *Settings → Security → Screen pinning*
and its **"Ask for PIN before unpinning"** option. Without that, pinning is only
a speed bump.
