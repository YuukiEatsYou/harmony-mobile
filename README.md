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

`.github/workflows/android.yml` builds a debug APK on every pull request and push
to `main`, and uploads it as a run artifact for download. Pushing a `v*` tag also
publishes a GitHub Release with the APK attached:

```sh
npm version 0.2.0 --no-git-tag-version
git commit -am "Release 0.2.0"
git tag v0.2.0
git push origin main v0.2.0
```

The artifact is a **debug** APK, signed with the debug key, which installs fine
for sideloading. A signed release build (with a keystore in repository secrets)
is the next step if this ever goes to a store. The Android `versionCode` and
`versionName` live in `android/app/build.gradle`; bump them alongside
`package.json` when cutting a release.

## Upstream

Changes this client needs from Harmony are written to `UPSTREAM_CHANGES.md`
(git-ignored). We do not edit Harmony from this repository.

## License

AGPL-3.0-or-later, matching Harmony.
