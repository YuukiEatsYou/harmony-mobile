import type { Server } from './servers';
import { openInstance } from './instance';

/**
 * A notification payload, mirroring docs/PUSH.md §6. Push is not implemented
 * server-side yet; this types the seam so the routing does not have to change
 * when it ships.
 */
export interface PushPayload {
  /** Routes to the right server; the origin is used as a fallback today. */
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

/**
 * Handles a notification tap: finds the instance it came from and opens it
 * deep-linked to the channel and message. Returns false when the instance is
 * not one this install knows, which the caller should ignore.
 */
export async function routePushPayload(
  payload: PushPayload,
  servers: Server[],
): Promise<boolean> {
  const server = servers.find(
    (candidate) =>
      candidate.instanceId === payload.instanceId || candidate.origin === payload.instanceId,
  );
  if (!server) return false;
  await openInstance(server.origin, {
    channelId: payload.channelId,
    messageId: payload.messageId,
  });
  return true;
}

/**
 * Wires the OS push provider. A no-op until push ships upstream
 * (docs/PUSH.md); the shape is deliberately here so wiring is additive:
 *
 * - Obtain one endpoint per install, via a local distributor (UnifiedPush/ntfy,
 *   the provider PUSH.md recommends first for a sideloaded APK).
 * - `PUT` it to every signed-in instance's `/users/@me/push-devices`.
 * - On a tap, call `routePushPayload` with the payload the provider delivered.
 */
export async function initNotifications(): Promise<void> {
  // Intentionally empty: no provider exists yet.
}
