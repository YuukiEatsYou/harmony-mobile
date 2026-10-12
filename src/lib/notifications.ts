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
  type PushPayload,
  type PushDeviceSender,
} from './push';

export type { PushPayload } from './push';

/**
 * The native provider bridge, implemented in Kotlin (`HarmonyPushPlugin`). The
 * provider is UnifiedPush/ntfy (docs/PUSH.md §2): a sideloaded APK has no Play
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
    eventName: 'pushTap',
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
 * Registers this install's endpoint with every stored instance. Called once at
 * boot and again for every instance added later; a provider that has no
 * distributor yet yields no endpoint and the call is a no-op (docs/PUSH.md §2).
 */
export async function registerPushEndpoint(servers: Server[]): Promise<void> {
  const endpoint = await currentEndpoint();
  if (!endpoint) return;
  const results = await registerEndpoint(endpoint, servers, nativeSender);
  for (const result of results) {
    if (!result.ok) {
      // A 401 means the user is not signed in on that instance yet, which is
      // expected; anything else is worth a line in the call diagnostics log.
      console.warn(`push: could not register with ${result.origin} (${result.status ?? 'no session'})`);
    }
  }
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

  // A tap that cold-started the app, buffered natively until the shell booted.
  // Drained after the listener is in place, so a live tap cannot be lost in the
  // gap.
  await drainPendingPushTap(getServers);

  await registerPushEndpoint(getServers());
}
