/**
 * The notification path: the provider seam, device registration, and tap
 * routing (docs/PUSH.md). The pure decisions — which instance a payload opens,
 * and the registration request — live in `push.ts`; this module wires them to
 * the native provider and to `openInstance`.
 */
import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core';
import type { Server } from './servers';
import { openInstance } from './instance';
import { findServer } from './server-model';
import {
  coercePushPayload,
  pushTargetKey,
  registerEndpoint,
  unregisterEndpoint,
  unregisteredServers,
  type PushPayload,
  type PushDeviceSender,
} from './push';

export type { PushPayload } from './push';

/**
 * The native provider bridge, implemented in Kotlin (`HarmonyPushPlugin`). The
 * provider is UnifiedPush (docs/PUSH.md §2): a sideloaded APK has no Play
 * services, so the install takes whatever distributor the user runs. The bridge
 * is deliberately small so the JS <-> Kotlin contract is explicit:
 *
 * - `getEndpoint` — one provider endpoint for this install, or null until a
 *   distributor has been chosen.
 * - `pushDevices` — performs one authenticated push-device request against an
 *   instance, using that instance's own session cookie (page JS cannot read it).
 * - `takePendingTap` / the `pushTap` event — the payload of a notification tap,
 *   buffered natively so a cold start is not lost before the shell boots. The
 *   event is only a signal; the payload is always read from `takePendingTap`,
 *   so a tap is routed exactly once.
 * - the `pushEndpoint` event — the provider produced or rotated this install's
 *   endpoint (it arrives asynchronously, after boot), so the shell re-registers.
 */
interface HarmonyPushPlugin {
  getEndpoint(): Promise<{ endpoint: string | null }>;
  pushDevices(options: {
    origin: string;
    path: string;
    method: string;
    body: unknown;
  }): Promise<{ ok: boolean; status?: number }>;
  takePendingTap(): Promise<{ payload: string | null }>;
  addListener(
    eventName: 'pushTap' | 'pushEndpoint',
    listenerFunc: (data: unknown) => void,
  ): Promise<PluginListenerHandle>;
}

const HarmonyPush = registerPlugin<HarmonyPushPlugin>('HarmonyPush');

/** Reads the provider's endpoint and, when it has none yet, does nothing. */
async function currentEndpoint(): Promise<string | null> {
  try {
    const { endpoint } = await HarmonyPush.getEndpoint();
    return endpoint;
  } catch {
    // A build with no provider wired (or a web preview) has no endpoint.
    return null;
  }
}

/** The native bridge as a `push.ts` sender. */
const nativeSender: PushDeviceSender = (origin, request) =>
  HarmonyPush.pushDevices({ origin, path: request.path, method: request.method, body: request.body });

/**
 * Handles one delivered payload string: parse, validate, and open the instance.
 * Returns false when it cannot be read or the instance is not known, which the
 * caller ignores (docs/PUSH.md §5) — an unknown instance is a normal outcome.
 */
export async function handleDeliveredPayload(raw: string, servers: Server[]): Promise<boolean> {
  let value: unknown;
  try {
    value = JSON.parse(raw) as unknown;
  } catch {
    return false;
  }
  const payload = coercePushPayload(value);
  if (!payload) return false;
  return routePushPayload(payload, servers);
}

/**
 * Handles a notification tap: finds the instance it came from and opens it
 * deep-linked to the channel and message. Returns false when the instance is
 * not one this install knows, which the caller should ignore.
 */
export async function routePushPayload(
  payload: PushPayload,
  servers: Server[],
): Promise<boolean> {
  const server = findServer(servers, pushTargetKey(payload));
  if (!server) return false;
  await openInstance(server.origin, {
    channelId: payload.channelId,
    messageId: payload.messageId,
  });
  return true;
}

/**
 * Origins already registered for the current endpoint. Registration is
 * idempotent server-side, but this keeps a resume (F3) or a re-add from
 * re-issuing a request per instance every time.
 */
const registered = new Set<string>();

/** The endpoint `registered` was built for; a rotation resets it. */
let registeredEndpoint: string | null = null;

/**
 * Registers this install's endpoint with every stored instance (docs/PUSH.md
 * §3). Called at boot, on app resume (an instance signed into after boot has a
 * session then), when an instance is added, and when the endpoint rotates. A
 * provider with no distributor yet yields no endpoint and the call is a no-op.
 */
export async function registerPushEndpoint(servers: Server[]): Promise<void> {
  const endpoint = await currentEndpoint();
  if (!endpoint) return;

  if (endpoint !== registeredEndpoint) {
    // First endpoint, or a rotation: everything must be re-registered.
    registered.clear();
    registeredEndpoint = endpoint;
  }
  const targets = unregisteredServers(servers, registered);
  if (targets.length === 0) return;

  const results = await registerEndpoint(endpoint, targets, nativeSender);
  for (const result of results) {
    if (result.ok) registered.add(result.origin);
    // A 401 means the user is not signed in on that instance yet, which is
    // expected; a later resume retries it. Anything else is logged.
    else console.warn(`push: could not register with ${result.origin} (${result.status ?? 'no session'})`);
  }
}

/**
 * Removes this install's endpoint from every given instance (docs/PUSH.md §3).
 * Called when the user removes an instance from the shell — the one sign-out
 * the shell *can* observe — while the origin's cookies still exist (F1).
 */
export async function unregisterPushEndpoint(servers: Server[]): Promise<void> {
  if (servers.length === 0) return;
  const endpoint = await currentEndpoint();
  if (!endpoint) return;
  await unregisterEndpoint(endpoint, servers, nativeSender);
  for (const server of servers) registered.delete(server.origin);
}

/**
 * Drains the natively-buffered tap payload, if any, and routes it. Called at
 * boot (a tap that cold-started the app) and on the provider's `pushTap` signal
 * (a tap that arrived while the app was running). `takePendingTap` clears the
 * buffer, so a payload is routed exactly once even if both fire.
 */
export async function drainPendingPushTap(getServers: () => Server[]): Promise<boolean> {
  let payload: string | null;
  try {
    ({ payload } = await HarmonyPush.takePendingTap());
  } catch {
    return false;
  }
  if (!payload) return false;
  return handleDeliveredPayload(payload, getServers());
}

/**
 * Wires the OS push provider. A no-op on the web preview, which has no provider
 * and no native session to register with (docs/PUSH.md §2/§3).
 */
export async function initNotifications(getServers: () => Server[]): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;

  // A tap that arrives while the app is running: the native side has already
  // buffered the payload and only signals that there is one.
  await HarmonyPush.addListener('pushTap', () => {
    void drainPendingPushTap(getServers);
  });

  // The provider hands us the endpoint asynchronously (it can arrive long after
  // boot), so registration is driven by the signal, not only by boot.
  await HarmonyPush.addListener('pushEndpoint', () => {
    void registerPushEndpoint(getServers());
  });

  // A tap that cold-started the app, buffered natively until the shell booted.
  // Drained after the listener is in place, so a live tap cannot be lost in the
  // gap.
  await drainPendingPushTap(getServers);

  // Fire-and-forget: registering one instance at a time over the native bridge
  // must not delay boot or the deep-link listener (F2). The endpoint signal and
  // the resume handler pick up anything not registered here.
  void registerPushEndpoint(getServers());
}
