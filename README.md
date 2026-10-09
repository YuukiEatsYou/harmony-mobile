# Harmony Mobile

A mobile client for **Harmony** that connects to **many servers at once**.
Harmony itself is one instance, one community; this app is a launcher that keeps
every instance you use, opening each server's own client, served from that
server.

It is the mobile sibling of
[harmony-desktop-electron](https://github.com/YuukiEatsYou/harmony-desktop-electron),
and works the same way.

## How it works

This repository does not vendor or reimplement Harmony. It is a small **shell**:

- A list of the instances you have added. The **+** flow verifies an address
  against `GET /api/v1/meta` and records the instance's name and icon.
- Tapping a server opens **that instance's own web client** from its own origin,
  full-screen, in a webview this app owns (`InstanceWebViewActivity`). Storage is
  per-origin, so one instance's cookies never leak into another's — and owning the
  webview is what lets us inject screen sharing (below).
- Because the client is loaded from the server, it is always current and there
  is no bundled copy to fall out of date. This is also why no CORS work is
  needed: every instance is loaded from its own origin.
- The shell remembers the instance you were last in and reopens it on the next
  launch, so leaving the app and coming back does not drop you at the selector.
  A `harmony://` deep link still wins, opening the instance it names.

### Screen sharing on Android

Chrome and the Android WebView implement no `getDisplayMedia`, which is why
screen sharing does not work in the installed PWA. The app fixes this natively:
it injects a `getDisplayMedia` shim into the instance webview, backed by Android's
MediaProjection, so the Harmony client — unmodified — gets a real video track.
Capture runs behind a foreground service (Android requires it for screen capture)
with a Stop action in the notification. The microphone for voice chat is
requested separately, on first use.

Notifications are designed for but not built yet — see `UPSTREAM_CHANGES.md`.

## Requirements

- Node.js 20 or newer
- To build the app: Android Studio, or a standalone Android SDK (platform 36,
  build-tools 36) with **JDK 17–24**. The Gradle 8.14.3 wrapper in this project
  cannot parse its build scripts on JDK 25+ (Java 27 fails with
  `Unsupported class file major version 71`), so an older JDK is required.
- A Harmony instance to point at. The app's `minSdk` is 26 (the in-app webview
  plugin's floor).

## Development

```sh
npm install
npm run dev        # the shell UI at http://127.0.0.1:5180
```

`npm run dev` runs the shell in an ordinary browser. That is enough to iterate on
its UI, but two things only work in the app: opening an instance (the web build
just opens a new tab) and adding a server against a real instance (a browser
enforces CORS, and Harmony sets no CORS headers). Test those on a device or
emulator.

### Checks

```sh
npm run typecheck   # tsc
npm run test        # plain-Node tests for the address and URL logic
npm run check       # typecheck + build
```

## Building the app

The native project lives in `android/`. Capacitor copies the built web assets
into it, so build the shell and sync first:

```sh
npm run sync         # build the shell, then `cap sync android`
npm run android      # the above, then open Android Studio
npm run apk:debug    # a debug APK under android/app/build/outputs/apk/debug/
```

To build from the command line without Android Studio, point
`android/local.properties` at your SDK (`sdk.dir=...`, git-ignored) and run Gradle
with a compatible JDK on `JAVA_HOME`:

```sh
cd android
JAVA_HOME=/path/to/jdk-21 ./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

### App icon and splash

The launcher icon and splash screens come from Harmony's own logo. The source is
`assets/icon.png`; the generated resources under `android/app/src/main/res` are
committed. To regenerate them after changing the source:

```sh
npm run assets
```

The command fetches `@capacitor/assets` on demand (it is deliberately not a
project dependency, since it pulls in a native image library and some advisories).
On npm versions that gate install scripts you may be asked to approve `sharp`.

## Reaching a local test instance

Cleartext http is permitted (see
`android/app/src/main/res/xml/network_security_config.xml`), so a plain-http
instance on your LAN works. Type the address in the add-server flow; without a
scheme, loopback and private addresses are assumed to be `http` and everything
else `https`.

From the **Android emulator**, your development machine's loopback is
`10.0.2.2`, so an instance on the host at port 8787 is `10.0.2.2:8787`. On a
physical device, use the machine's LAN address, e.g. `192.168.1.50:8787`.

## Layout

- `src/` — the shell (plain TypeScript, no framework).
  - `src/lib/meta.ts` — address parsing and the `meta` probe.
  - `src/lib/servers.ts` — the stored instance list.
  - `src/lib/instance.ts` — opening an instance in the in-app webview.
  - `src/lib/urls.ts` — pure URL and `harmony://` deep-link helpers.
  - `src/lib/notifications.ts`, `src/lib/deeplink.ts` — the seams for
    notifications and deep links.
- `android/` — the Capacitor Android project. Screen sharing lives here:
  `InstanceWebViewActivity.kt` (the instance webview and shim injection),
  `ScreenShareService.kt` (MediaProjection capture), `ScreenShareSender.kt` (the
  shim bridge), and `app/src/main/assets/screen-share-shim.js` (the
  `getDisplayMedia` polyfill).
- `scripts/` — plain-Node tests.
- `harmony/` — a local Harmony checkout to test against (git-ignored).

## Releasing

`.github/workflows/android.yml` builds a **debug** APK on every pull request and
push to `main` and uploads it as a run artifact. Pushing a `v*` tag additionally
builds a **signed release** APK and publishes a GitHub Release with it attached.

### One-time setup: the signing key

A release APK has to be signed, and the key must be kept safe: Android refuses to
update an install with an APK signed by a different key, so losing it means you
can never update the app in place again. Create one and store it in the
repository's Actions secrets:

```sh
keytool -genkeypair -v -keystore release.keystore -alias harmony \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore   # copy the whole line this prints
```

Add these under Settings -> Secrets and variables -> Actions:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | the base64 output above |
| `ANDROID_KEYSTORE_PASSWORD` | the store password you chose |
| `ANDROID_KEY_ALIAS` | `harmony` |
| `ANDROID_KEY_PASSWORD` | the key password you chose |

The keystore itself is never committed (`*.keystore` and `*.jks` are ignored).

### Cutting a release

```sh
git tag v0.2.0
git push origin v0.2.0
```

The tag is the source of truth for the version: `versionName` becomes `0.2.0`, and
`versionCode` is derived from it as `major*10000 + minor*100 + patch`, so keep
tags plain `vX.Y.Z`. The signed APK lands on the Releases page as
`harmony-mobile-<version>.apk`.

A release APK is signed with your key rather than the debug key, so it will not
install over a debug build -- uninstall the debug one first. Locally,
`./gradlew assembleRelease` works without a keystore but produces an unsigned APK.

## Upstream

Changes this client needs from Harmony are written to `UPSTREAM_CHANGES.md`
(git-ignored). We do not edit Harmony from this repository.

## License

AGPL-3.0-or-later, matching Harmony.
