# Notifications and push

How the shell delivers a notification from a Harmony instance to the right
channel, and what the server / web client has to provide for it. The client
half is a **seam** (`src/lib/notifications.ts`): the routing and the payload
type are here, the provider is not wired yet. This document is the contract both
sides are written against, so the router does not have to change when push
ships.

Status: **designed, not built.** The upstream work this needs is recorded in
`UPSTREAM_CHANGES.md` (git-ignored hand-off notes); this repository never edits
Harmony.

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
  the recommended first provider for a sideloaded build.
- The provider hands the install an endpoint and a delivery callback; the shell
  does not care which provider, only that it can produce one endpoint per
  install.

`initNotifications()` (`src/lib/notifications.ts`) is where the provider is
wired. It is deliberately empty today.

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
instance the user has added. These routes do not exist upstream yet.

## §4 — Delivery

The server decides who to notify and sends the payload below to the endpoint.
The payload is self-contained: it names the instance it came from so the shell
can route it without talking to the server first, which matters because the
tap may open the app cold, off the network, or with the instance unreachable.

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

## §6 — Payload shape

Mirrored by the `PushPayload` type in `src/lib/notifications.ts`:

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
