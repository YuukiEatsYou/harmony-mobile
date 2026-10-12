/**
 * The notification/push contract, mirroring `docs/PUSH.md`. Kept free of any
 * Capacitor import (the reason `server-model.ts` and `urls.ts` are separate) so
 * the routing and the device-registration logic can be tested in plain Node —
 * see `scripts/push-test.mjs`.
 *
 * The transport that actually reaches an instance is native: the push-device
 * route is authenticated with the instance's own httpOnly session cookie, which
 * page JavaScript cannot read (`SessionCookie`, `docs/PUSH.md` §3). This module
 * is only the shape and the decisions; `notifications.ts` wires the native side.
 */
import type { Server } from './servers';
import type { ServerKey } from './server-model';

/** The platform value the server records for this build (docs/PUSH.md §3). */
export const PUSH_PLATFORM = 'android';

/** The per-account push-device route on an instance (docs/PUSH.md §3). */
export const PUSH_DEVICES_PATH = '/users/@me/push-devices';

/**
 * A notification payload, mirroring docs/PUSH.md §6. The server side that fixes
 * these names is `PushPayload` in Harmony's shared package (upstream, YUC-8);
 * keep them stable — the router reads them by name.
 */
export interface PushPayload {
  /** Routing key: the instance's stable id, or its origin as the fallback. */
  instanceId: string;
  instanceName: string;
  iconUrl: string | null;
  channelId: string;
  channelName: string;
  messageId: string;
  kind: 'mention' | 'reply';
  authorName: string;
  snippet: string | null;
}

/** A non-empty string, or null. */
function text(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

/**
 * Validates and normalizes a payload from the wire — the provider's cable, or
 * the JSON an Android intent carried. This is a trust boundary: a payload that
 * cannot be routed (no `instanceId`, `channelId` or `messageId`) is dropped
 * rather than half-opened, and the display-only fields (docs/PUSH.md §6) fall
 * back to safe values so the router never reads `undefined`.
 */
export function coercePushPayload(value: unknown): PushPayload | null {
  if (typeof value !== 'object' || value === null) return null;
  const raw = value as Record<string, unknown>;
  const instanceId = text(raw.instanceId);
  const channelId = text(raw.channelId);
  const messageId = text(raw.messageId);
  if (!instanceId || !channelId || !messageId) return null;
  return {
    instanceId,
    channelId,
    messageId,
    kind: raw.kind === 'reply' ? 'reply' : 'mention',
    instanceName: text(raw.instanceName) ?? instanceId,
    channelName: text(raw.channelName) ?? '',
    iconUrl: text(raw.iconUrl),
    authorName: text(raw.authorName) ?? '',
    snippet: text(raw.snippet),
  };
}

/**
 * The instance-selection key a payload routes by (docs/PUSH.md §5).
 * `payload.instanceId` is the routing key: the stable id when the server sends
 * one, otherwise the origin the sender put there, so the same key is tried as
 * both. Pass the result to `findServer` (`server-model.ts`) to get the entry.
 */
export function pushTargetKey(payload: PushPayload): Required<ServerKey> {
  return { instanceId: payload.instanceId, origin: payload.instanceId };
}

/** One push-device request against an instance (docs/PUSH.md §3). */
export interface PushDeviceRequest {
  path: string;
  method: 'PUT' | 'DELETE';
  body: Record<string, unknown>;
}

/** Registering this install's endpoint with an instance. */
export function registerPushDevice(endpoint: string): PushDeviceRequest {
  return { path: PUSH_DEVICES_PATH, method: 'PUT', body: { endpoint, platform: PUSH_PLATFORM } };
}

/** Removing this install's endpoint from an instance (sign-out or rotation). */
export function unregisterPushDevice(endpoint: string): PushDeviceRequest {
  return { path: PUSH_DEVICES_PATH, method: 'DELETE', body: { endpoint } };
}

/** The per-instance result of a registration or removal. */
export interface PushDeviceResult {
  origin: string;
  ok: boolean;
  status?: number;
}

/** How a request reaches one instance; the native bridge supplies this. */
export type PushDeviceSender = (
  origin: string,
  request: PushDeviceRequest,
) => Promise<{ ok: boolean; status?: number }>;

/**
 * Sends one push-device request to every stored instance, in order. A failure
 * against one instance never stops the others: an instance the user is not
 * signed in to answers 401, which is a normal per-instance outcome (the endpoint
 * is still registered where there is a session), not a fatal error.
 */
export async function sendPushDevice(
  request: PushDeviceRequest,
  servers: Server[],
  send: PushDeviceSender,
): Promise<PushDeviceResult[]> {
  const results: PushDeviceResult[] = [];
  for (const server of servers) {
    try {
      const { ok, status } = await send(server.origin, request);
      results.push({ origin: server.origin, ok, status });
    } catch {
      // A rejected bridge call (no session, offline) is a per-instance failure.
      results.push({ origin: server.origin, ok: false });
    }
  }
  return results;
}

/** Registers `endpoint` with every stored instance (docs/PUSH.md §3). */
export function registerEndpoint(
  endpoint: string,
  servers: Server[],
  send: PushDeviceSender,
): Promise<PushDeviceResult[]> {
  return sendPushDevice(registerPushDevice(endpoint), servers, send);
}

/** Removes `endpoint` from every stored instance (sign-out or rotation). */
export function unregisterEndpoint(
  endpoint: string,
  servers: Server[],
  send: PushDeviceSender,
): Promise<PushDeviceResult[]> {
  return sendPushDevice(unregisterPushDevice(endpoint), servers, send);
}
