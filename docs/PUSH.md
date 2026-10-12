# Notifications and push

How the shell delivers a notification from a Harmony instance to the right
channel, and what the server / web client has to provide for it. The client
half is a **seam**: the pure contract (payload shape, routing key, device
request) is `src/lib/push.ts`; the provider wiring and the tap routing are
`src/lib/notifications.ts`. This document is the contract both sides are written
against.

Status: **wired end to end against the UnifiedPush connector.** Routing, device
registration, and the provider adapter (connector + service) are implemented;
the on-device tap check is the remaining verification. The upstream work this
needs is recorded in `UPSTREAM_CHANGES.md` (git-ignored hand-off notes); this
repository never edits Harmony.

## Why a launcher needs this

The shell opens each instance's own client in a webview (`InstanceWebViewActivity`).
That webview has no push channel of its own and is not alive when the app is
backgrounded, so a mention that arrives while the app is closed cannot reach the
client. The shell therefore subscribes once per install, receives the mention
itself, and opens the instance deep-linked to the channel and message. It is the
mobile sibling of the desktop client's notification path.

## §1 — A stable instance id

The routing key is an instance's stable `instanceId`, served in
`GET /api/v1/meta`:

```json
{ "name": "Example", "apiVersion": "1", "instanceId": "..." }
```

The client stores it on each entry it keeps (`Server.instanceId`,
`src/lib/servers.ts`) and routes a tap by it (`findServer`). The **origin is the
fallback** while a server does not send one: today's servers do not, so a tap
still works as long as the instance has not moved. A stable id survives a host
change (a LAN address vs a public one, a new domain), which the origin cannot.

`meta.instanceId` is optional in the client and an instance that omits it keeps
working — it just cannot be told apart from the same instance reached at another
address.

## §2 — The provider

A sideloaded APK has no Google Play services, so the client expects a
provider-agnostic push channel rather than FCM:

- **UnifiedPush** or **ntfy**, choosing a distributor the user already runs, is
  the recommended first provider for a sideloaded build. **UnifiedPush is the
  chosen provider** for this client.
- The provider hands the install an endpoint and a delivery callback; the shell
  does not care which provider, only that it can produce one endpoint per
  install.

`initNotifications()` (`src/lib/notifications.ts`) wires the provider. The
provider runs natively, because the whole request set — the register, the tap —
is only reachable with the instance's own session, which page JavaScript cannot
read:

- **Subscription** — `PushProvider.ensureRegistered` (`MainActivity.onCreate`)
  registers with the current or default distributor via the UnifiedPush
  connector, when one is installed. With none installed it is a no-op and the
  path stays dormant.
- **Reception** — `HarmonyPushService` (a UnifiedPush `PushService`) receives an
  endpoint (`onNewEndpoint`) and deliveries (`onMessage`) and writes them into
  `PushEndpoint` and `PushStore`, the same seam the debug path uses.
- **The shell reads them** through `HarmonyPushPlugin`
  (`getEndpoint` / `pushDevices` / `takePendingTap`), signalled by a payload-free
  `pushTap` / `pushEndpoint` event; the endpoint is read, never copied, and the
  payload is drained once.

The endpoint can arrive after boot, so registration is driven by the
`pushEndpoint` signal and by app resume, not only by boot.

## §3 — Registering the device

The install registers its endpoint with **every signed-in instance**, per
account:

```
PUT /users/@me/push-devices
{ "endpoint": "<provider endpoint>", "platform": "android" }
```

and removes it on sign-out or when the endpoint rotates:

```
DELETE /users/@me/push-devices
{ "endpoint": "<provider endpoint>" }
```

The endpoint is per install, not per instance, so it is registered once per
instance the user has added. The client now does exactly this: `initNotifications`
registers at boot, `main.ts` registers a newly added instance, and `push.ts`
(`registerEndpoint` / `unregisterEndpoint`) fans the one request out to every
instance and tolerates a per-instance failure (a 401 there is just "not signed
in here"). The request is carried natively (`pushDevices` → `PushDevices`) with
each instance's own session cookie. These routes ship in Harmony (YUC-8:
`PUT`/`DELETE /api/v1/users/@me/push-devices`).

Removal happens in two places. **Endpoint rotation:** a new endpoint
re-registers and the old one is deleted. **Removing an instance** from the shell
(`main.ts` `remove()` → `unregisterPushEndpoint`) deletes the endpoint from that
instance while its cookies still exist — the one sign-out the shell can observe.
A **sign-out inside an instance's webview** has no seam; the shell does not
observe it, so that stale device is cleaned up on the next rotation or when the
account is removed upstream.

## §4 — Delivery

The server decides who to notify and sends the payload below to the endpoint.
The payload is self-contained: it names the instance it came from so the shell
can route it without talking to the server first, which matters because the
tap may open the app cold, off the network, or with the instance unreachable.

On delivery, `HarmonyPushService.onMessage` raises a tray notification
(`PushNotifications`) that carries the raw payload on its content intent; the
**tap** is what routes. It does not open the instance by itself, so a delivered
message is routed exactly once — from the tap.

### Presentation (YUC-21)

The wording, icon, permission timing, and foreground behaviour follow the UX
review:

- **Title** = `authorName`, else `channelName`, else `instanceName`, else
  `Harmony` — author-first, because Android already renders the app label as the
  header and the bold slot's job is the strongest identifier. **Body** =
  `Mentioned you in #<channel>: <snippet>` (`Replied to you …` for a reply);
  the `: <snippet>` and ` in #<channel>` parts drop when their value is blank.
  **SubText** = `instanceName`, because the app is multi-instance. An
  unparseable payload shows `Harmony` / `New message`. The mapping is the pure
  `PushText.summarize` (unit-tested on the JVM in `PushTextTest`); parsing at the
  trust boundary stays in `PushNotifications`.
- **Icon**: a dedicated monochrome `res/drawable/ic_stat_harmony.xml` (the
  three-pebble mark), not the system placeholder. Notification small icons are
  alpha-only, so it is a single white fill the system tints.
- **Category** `MESSAGE`, so Do Not Disturb and ranking treat it as a message;
  the channel is `Messages` at `IMPORTANCE_DEFAULT`.
- **Permission**: `POST_NOTIFICATIONS` is asked once, in context, on the first
  instance open (`InstanceWebViewActivity.requestNotificationsOnce`), guarded by
  a persisted flag (`NotificationPermission`) so a denial is not re-asked. It is
  **not** asked at launch, when the shell is only the selector. The call and
  screen-share flows keep their own causal asks, which serve the foreground
  service.
- **Foreground**: the tray is suppressed while any app activity is started
  (`AppForeground`, fed by `HarmonyApplication`). The instance's own client
  already surfaces a delivery in-app, so a tray post would double-signal. With
  no `POST_NOTIFICATIONS` grant there is nothing to tap, so a backgrounded
  delivery is dropped rather than mis-routed later.

## §5 — Tap routing

On a tap, the shell calls `routePushPayload(payload, servers)`:

1. Find the server whose `instanceId` (or, failing that, `origin`) matches
   `payload.instanceId`.
2. Open it through `openInstance(origin, { channelId, messageId })`, which
   loads the client at its origin with the URL parameters in §7 appended.
3. Return `false` when no stored server matches, so the caller ignores a
   notification for an instance this install does not have.

The result is deliberately a boolean, not an error: an unknown instance is a
normal outcome (the user removed it), not a failure.

A tap is a tray-notification tap whose content intent carries the payload
(§4). The shell cannot read it before it boots, so the native side buffers it
(`PushStore`) and signals the shell: a tap that cold-started the app is drained
at `initNotifications`, and a tap while the app is already alive drains on the
`pushTap` signal. `takePendingTap` clears the buffer, so a payload is routed
exactly once. The payload is validated and normalized before routing
(`coercePushPayload`), so a malformed or truncated delivery is dropped rather
than opening the wrong place.

## §6 — Payload shape

Mirrored by the `PushPayload` type in `src/lib/push.ts` (re-exported from
`src/lib/notifications.ts`):

| Field | Meaning |
| --- | --- |
| `instanceId` | Routing key; the instance's §1 id, or its origin as fallback. |
| `instanceName` | Display name, for a fallback when the icon is missing. |
| `iconUrl` | Instance icon, or `null`. |
| `channelId` | Channel to open. |
| `channelName` | Channel name, for the notification text. |
| `messageId` | Message to scroll to. |
| `kind` | `mention` or `reply`. |
| `authorName` | Who sent it. |
| `snippet` | Short preview, or `null`. |

The client only requires `instanceId`, `channelId` and `messageId` to route;
the rest is display metadata. Keep the field names stable — the router reads
them by name.

## §7 — Instance URL parameters

`buildInstanceUrl` (`src/lib/urls.ts`) appends the destination to the instance's
own client URL:

```
https://chat.example.com/?channel=<channelId>&message=<messageId>
```

`message` is only meaningful with `channel`. Today's client has no URL routing,
so the parameters are inert; upstream should read them on load to open the
channel and scroll to the message. The same parameters make a `harmony://` deep
link work (`parseDeepLink`), so landing from a notification and landing from a
link share one code path.
