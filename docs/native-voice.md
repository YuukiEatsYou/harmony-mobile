# Native voice

The goal is a voice call that belongs to the app rather than to any screen, so
backgrounding and reopening the app never interrupts it — the behavior people
correctly expect from a calling app, and the one thing the instance WebView
cannot provide (the call dies with the activity that owns the WebView).

This document is the plan and the contract. It is grounded in the current
Harmony client (`apps/web/src/lib/voice.svelte.ts`) and server
(`apps/server/src/voice/`), which the native side must mirror.

## The protocol

Voice is an SFU: the server relays encoded audio between members and never
decodes it. The client's whole side is one `RTCPeerConnection` to the server.

**HTTP** (all under the instance origin, authenticated):

| Route | Purpose |
| --- | --- |
| `GET /api/v1/voice` | every occupied voice room and its roster |
| `POST /api/v1/channels/:id/voice` | join (or move); returns the room |
| `POST /api/v1/channels/:id/voice/answer` | the SDP answer to the server's offer |
| `PATCH /api/v1/channels/:id/voice` | `muted`, `deafened`, `sharing`, `watching` |
| `DELETE /api/v1/channels/:id/voice` | leave |

**Gateway** (the same `/gateway` WebSocket chat uses):

- `VOICE_SIGNAL` — `{ channelId, sdp }`, an SDP **offer** from the server to this
  user's sessions. The server is the only offerer (no glare).
- `VOICE_STATE_UPDATE` — `{ channelId, members }`, the full roster of a room.
- `READY` — the first dispatch after IDENTIFY, sent only once the server has
  registered the session. Anything that causes the server to dispatch to this
  member (including the member's own voice offers) must wait for it: joining on
  HELLO races the offer, which is then dispatched to sessions that do not include
  this one yet, and is lost with no retry.

**Media shape** — the server's offer contains, per member:

- one `recvonly` **audio** line and one `recvonly` **video** line for the
  member's own outgoing media (the client's mic and, later, its screen);
- for every *other* member, one `sendonly` **audio** and one `sendonly`
  **video** line, each with the msid/stream id set to that member's **user id**.

Consequences the native side must honor:

- **Non-trickle.** There is no candidate endpoint. `setLocalDescription` must be
  followed by waiting for ICE gathering to complete before the answer SDP is
  posted, or the answer carries no candidates and ICE never connects.
- **Own video line.** The member's own `recvonly` video transceiver must be
  flipped to `sendonly` at answer time, so attaching a screen later is a
  `replaceTrack` with no renegotiation.
- **Track identity.** Incoming streams are keyed by the producer's user id, which
  is what drives per-speaker UI.
- **No STUN/TURN** is configured by the web client; media is direct UDP to the
  server's ICE host candidate (the server advertises `voicePublicIp` when behind
  NAT). Native uses the same empty ICE config.
- **A repeat join can be a no-op.** If the member is already listed in the room
  and another session still holds it, `POST …/voice` returns the room **without
  sending a new offer** (`service.ts`). Native voice therefore sends `DELETE`
  first, so it takes the seat and gets a fresh connection — which also means the
  WebView must not be in the same channel at the same time.
- **Codec.** Whatever WebRTC negotiates — Opus audio, VP8/VP9/H.264 video. The
  SFU forwards payloads unchanged.
- **Screen share negotiates nothing.** Start = `replaceTrack(screenTrack)` on the
  pre-negotiated sendonly video line + `PATCH { sharing: true }`. Watching is
  opt-in via `PATCH { watching: [ids] }`; the relay forwards nothing otherwise.

## Host call state to the client (v1)

While the shell runs voice natively, the client's sidebar cannot see the call
from the server alone (the client is kept out of voice; see `UPSTREAM_CHANGES`
§6). The shell therefore pushes the call state to the client over the same
`HarmonyVoiceHost` bridge the client uses to start a call. The client assigns
`window.HarmonyVoiceHost.onState = (message) => {}` when it starts, and also
accepts the payload as a `harmony:voice-state` window event; the shell calls the
callback, or dispatches the event when there is no callback. The client accepts
the message as an object or a JSON string.

```json
{
  "version": 1,
  "type": "state",
  "channelId": "<voice channel id>",
  "channelName": "<display name or null>",
  "self": { "muted": false, "deafened": false, "sharing": false },
  "members": [
    { "id": "<user id>", "name": "<display name>", "muted": false,
      "sharing": false, "speaking": false }
  ]
}
```

`members` is the full roster, this member included (it mirrors
`VOICE_STATE_UPDATE`); `speaking` is the shell's own reading of the inbound RTP
`audioLevel`. `deafened` is only on `self`. A terminal
`{ "version": 1, "type": "left" }` clears the row when the call ends.

The client drops anything that is not `version: 1` with a known `type`, or a
`state` without a `channelId`; unknown fields are ignored, so the payload can
grow without breaking the client. The shell builds the message in
`VoiceHostEmitter` from `VoiceCall` (`channelId` is carried on
`VoiceCall.State`).

## Architecture

```
InstanceWebViewActivity ── hosts ──> WebView (chat client, unchanged)
        │ reads session cookie
        ▼
  VoiceSignaling  (OkHttp: /gateway WS + HTTP; auth by cookie)
        │ offers / rosters / join / answer / patch / leave
        ▼
  NativeVoiceSession  (org.webrtc: PeerConnection, mic, playback, screen)
        │
        ▼
  CallService  (foreground service, type microphone [+ mediaProjection])
        │
        ▼
  Native call UI  (in-call controls; survives activity destruction)
```

- **Auth without a token.** The web client authenticates with an httpOnly
  session cookie that JavaScript cannot read, but Android's `CookieManager` can
  (`getCookie(origin)` returns httpOnly cookies too). Native reads it for that
  instance's origin and sends it as a `Cookie` header on both the API requests
  and the WebSocket handshake. Wait for login before starting a call.
- **Its own gateway.** Offers are dispatched to every session of the user, so
  native needs its own `/gateway` connection to receive them; the webview will
  not forward them. The web client's own voice store ignores an offer whose
  `channelId` differs from the channel it joined, so a native join does not
  disturb it — the sidebar still shows the member in the room via
  `VOICE_STATE_UPDATE`.
- **The service owns the session.** `NativeVoiceSession` lives in `CallService`,
  so the call is independent of any activity. The existing `CallWatcher` can go
  away for native calls (the service no longer depends on a WebView reporting
  that a call exists).

## Milestones

1. **Signaling + auth — implemented.** Session cookie read from the
   `CookieManager` (`SessionCookie.kt`), HTTP surface (`VoiceApi.kt`), gateway
   connection with IDENTIFY/heartbeat/reconnect (`VoiceSignaling.kt`), gathered
   behind `NativeVoice.kt`. No media yet. See "How to verify milestone 1".
2. **Audio — done.** Mic capture, the peer connection, non-trickle answer, remote
   playback, mute/deafen, owned by `CallService` (foreground, type `microphone`).
   Verified on a device: `connection CONNECTED`, RTP counters climbing both ways,
   and `micEnergy` rising — real capture. The foreground service is what keeps the
   microphone unmuted, and the client's voice is switched off so the two do not
   fight for it.
3. **Call UI — done.** Calls start from the client's own channel list: the
   shell injects a `HarmonyVoiceHost` bridge and the client hands a voice-channel
   tap to it (`UPSTREAM_CHANGES` §7), so there is no separate app button (verified
   over the DevTools protocol by clicking the sidebar row). A call shows a native
   control bar along the top of the instance (`VoiceCallBar`) — channel, state,
   mute, deafen, share, leave — and the fuller in-call screen (`CallActivity`, with
   the roster) opens from the call notification.

   The bridge is two-way: the shell also pushes the native call state back with
   `VoiceHostEmitter`, on the v1 contract below, so the client's own sidebar row
   reads as connected, shows its muted/deafened state and the roster, and ends the
   call when the connected row is tapped. Because the client re-assigns its
   handler asynchronously after it boots and there is no client-to-shell sync
   request in v1, the shell re-sends the current state on every change, when the
   page (re)loads, when the instance is resumed, and on a short interval while a
   call is live — so a call that is already running when the client reloads still
   shows, and a call that ends leaves no stale row.
4. **Screen share — implemented; needs a two-party check.** Sharing attaches a
   `ScreenCapturerAndroid` capture to this member's own send-only video line
   (`replaceTrack`) and sets `PATCH { sharing: true }`; watching opts in with
   `PATCH { watching }` and renders the relayed track in `VoiceViewActivity`. The
   call bar shows a **Screen** indicator when anyone is sharing (tapping it opens the
   voice view), and its channel name also opens the voice view — the shared screen
   fitted whole inside the view (tap it to hide the chrome and system bars, tap
   again to bring them back; a Fullscreen control does the same), or the roster as
   a grid of avatars with a ring on whoever is speaking (from the inbound RTP
   `audioLevel`). Not yet exercised end
   to end: sharing needs a screen-capture consent tap and a second participant.

## Integration decision (open)

How does a person start a call? The channel list lives in the WebView today.

- **Native call surface** (preferred): a native voice screen lists voice channels
  (from `GET /api/v1/channels`) and joins; then it owns the in-call UI. No
  upstream change, aligned with the eventual custom front end.
- **Delegate from the WebView**: the client's voice store calls into a native
  bridge on join. Nicer UX (the existing sidebar drives it) but a real upstream
  change and the client would have to render state it no longer owns.

Milestones 1–2 do not depend on this decision.

Resolved: the shell sets `window.__harmonyVoiceDisabled = true` from its injected
document-start script (`assets/screen-share-shim.js`), which the client reads (its
`lib/voice-mode.ts`). It keeps the sidebar rosters but never joins or resumes a
call, so it does not take the microphone or the seat the native client needs —
without it the two captures fight and the platform mutes the native one.

## How to verify milestone 1

The signaling can be exercised on a device without any call UI yet:

1. Open an instance and sign in, so the webview has a session cookie.
2. Hold the **Harmony** title on the server list to open the call diagnostics.
3. Tap **Voice test**, then **Refresh**.

What it should log: `native voice: connecting to <origin>`, then
`signaling connected`, then `session ready`, then
`rooms: N room(s), M member(s)`. If the cookie is missing
it says so; if auth fails the gateway closes with code 4004 and that is logged.
Seed a voice room from the web client (join a channel there) and the roster line
and an `offer for <channel>` (from the webview's own join) should appear too —
proof the native socket is receiving the same dispatches.

Nothing joins a channel yet; that is milestone 2, when there is media to answer
with.

## How to verify milestone 2

Same place: hold **Harmony** → **Voice join**, then **Refresh**. It prefers a
voice room someone is already in (so a second device can hear it immediately) and
otherwise joins the first voice channel.

Expected log:

```
native voice: joining <channelId>          (occupied or empty voice channel)
native voice: media ready
native voice: signaling connected
native voice: session ready
native voice: joined <channelId>
native voice: offer (<n> chars)             the SFU's offer
native voice: answered (<n> chars)          the non-trickle answer
native voice: ice CONNECTED                 media is flowing
native voice: track from <userId>           someone else's audio arriving
```

To hear it, have a **second account** (or another person) in the same channel —
the SFU relays encoded audio between members. Note the phone's own session cannot
be in the same room twice: joining natively moves the seat the webview held, so
test against a different account, not the same one in two places.

If `answered` never appears, the offer/answer step is failing; if `ice` never
reaches `CONNECTED`, the answer likely went out before ICE gathering finished, or
the server's candidates did not reach the host.

## Native platform gotchas

Things that cost real debugging time here; none is visible from the browser
implementation:

- **`ACCESS_NETWORK_STATE` is required.** libwebrtc's `NetworkMonitor` reads
  `ConnectivityManager`. Without the permission it throws `SecurityException`
  inside a JNI callback, which the JNI exception handler turns into
  `RTC_CHECK(false)` → `SIGABRT` (`sdk/android/src/jni/jvm.cc:81`). The Java stack
  appears only as `W System.err` in logcat, not in the tombstone.
- **The factory needs audio *and* video codec factories.** The SFU's offer carries
  video lines (the member's own screen plus one per other member), so a factory
  built without them negotiates those m-lines against an empty codec list and
  aborts with `libc++ Hardening assertion !empty() failed: front() called on an
  empty vector`. `VoiceEngine` sets `BuiltinAudio*FactoryFactory` and
  `DefaultVideo*Factory` (video needs an `EglBase`).
- **A communication call routes to the earpiece by default.** WebRTC sets
  `MODE_IN_COMMUNICATION`, which Android sends to the earpiece — right for a phone
  call, wrong for a voice room. The output is chosen explicitly (`VoiceAudio`): the
  speaker by default, switchable, since a device's default was audibly wrong.
- **The call must not be tied to the instance activity.** The instance webview is
  destroyed when the app is reopened, so `InstanceWebViewActivity.onDestroy` must
  not stop `CallService`; the service owns the call precisely so it outlives the
  activity. Stopping it there dropped the call on every reopen.
- **A `SurfaceViewRenderer` fills its view by default.** `setScalingType(FIT)` alone
  did not letterbox on device — the view was sized to the video's own aspect
  instead (`VoiceViewActivity.fitVideo`, measured from the first frame through a
  `VideoSink`), so the view *is* the letterbox and the picture is never cropped.
  The hardware scaler also clips: it grows the surface to the video's aspect at
  full height, so it is left off.
- **A MediaProjection needs a `mediaProjection` foreground service.** Android 14+
  refuses `getMediaProjection` unless a foreground service of type
  `mediaProjection` is already running, so `CallService` holds
  `microphone|mediaProjection` while sharing and drops back to `microphone` after.
- **A "connected" call can be silent.** Check the RTP counters, not just
  `connection CONNECTED`; `NativeVoiceSession` samples them for this reason.
- **The foreground service is what unmutes the microphone.** An app that is not in
  the foreground (and has no foreground service of type `microphone`) has its
  capture silenced by Android — `AudioRecordImpl … [mute]` in logcat and
  `micEnergy=0` in the stats. This is why the call runs in `CallService`.
- **The client's own voice starves the native call.** A member holds one seat and
  one app holds the microphone, so while the WebView client is in a voice call
  (including its auto-rejoin after a reopen) the native capture is muted. The shell
  must keep the client out of voice; see UPSTREAM_CHANGES §6.

## Risks

- **Detached-WebView detour is avoided** by owning media natively, but the
  service and battery exemption are still required (any app).
- **WebRTC build size.** The SDK adds tens of MB to the APK; enable ABI splits or
  an App Bundle later if it matters.
- **Audio routing.** `AudioManager` mode/route (earpiece vs speaker vs
  Bluetooth) is now ours to set; WebRTC's Android audio device does most of it,
  but it must be verified against Bluetooth headsets.
- **Non-trickle correctness.** The answer must not be posted until ICE gathering
  completes; this is the most likely cause of a call that "connects" but is
  silent.
