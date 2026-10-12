/**
 * The pure parts of the instance list: merging, dropping, and selecting an
 * entry. Kept free of the Capacitor `Preferences` import — the reason
 * `urls.ts` is separate from `instance.ts` — so they can be tested in plain
 * Node (see scripts/servers-test.mjs).
 */
import type { Server } from './servers';

/** The key a deep link or notification uses to pick an instance. */
export interface ServerKey {
  instanceId?: string | null;
  origin?: string | null;
}

/**
 * Replaces any existing entry for the same origin, then appends. One origin
 * holds one entry, so re-adding an instance updates it instead of duplicating.
 */
export function mergeServer(servers: Server[], server: Server): Server[] {
  const next = servers.filter((existing) => existing.origin !== server.origin);
  next.push(server);
  return next;
}

/** Removes the entry with `id`, leaving the rest in place. */
export function dropServer(servers: Server[], id: string): Server[] {
  return servers.filter((server) => server.id !== id);
}

/**
 * Finds the stored entry a deep link or notification points at: by
 * `instanceId` first (the stable id, docs/PUSH.md §1), then by `origin` (the
 * fallback for a server that does not send one). Returns null when this install
 * does not have the instance.
 */
export function findServer(servers: Server[], key: ServerKey): Server | null {
  const { instanceId, origin } = key;
  if (instanceId != null) {
    const byId = servers.find((candidate) => candidate.instanceId === instanceId);
    if (byId) return byId;
  }
  if (origin != null) {
    return servers.find((candidate) => candidate.origin === origin) ?? null;
  }
  return null;
}
