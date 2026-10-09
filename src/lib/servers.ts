import { Preferences } from '@capacitor/preferences';
import type { InstanceMeta } from './meta';

/** One signed-in instance, as kept by the launcher. */
export interface Server {
  /** Local id, stable for the life of the entry. */
  id: string;
  /** The instance origin, e.g. `https://chat.example.com` or `http://10.0.2.2:8787`. */
  origin: string;
  /** Display name from `meta`, or the origin when it has none. */
  name: string;
  iconHash: string | null;
  theme: { background: string | null; accent: string | null } | null;
  /** Stable instance id from `meta` when the server provides one (docs/PUSH.md §1). */
  instanceId: string | null;
  addedAt: string;
}

const SERVERS_KEY = 'harmony.servers.v1';

export function newId(): string {
  const cryptoApi = globalThis.crypto;
  if (cryptoApi && typeof cryptoApi.randomUUID === 'function') {
    return cryptoApi.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** Builds a store entry from a validated instance. */
export function serverFromMeta(origin: string, meta: InstanceMeta): Server {
  return {
    id: newId(),
    origin,
    name: meta.name.trim() || origin,
    iconHash: meta.iconHash ?? null,
    theme: meta.theme ?? null,
    instanceId: meta.instanceId ?? null,
    addedAt: new Date().toISOString(),
  };
}

export async function loadServers(): Promise<Server[]> {
  const { value } = await Preferences.get({ key: SERVERS_KEY });
  if (!value) return [];
  try {
    const parsed = JSON.parse(value) as unknown;
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(isServer);
  } catch {
    return [];
  }
}

export async function saveServers(servers: Server[]): Promise<void> {
  await Preferences.set({ key: SERVERS_KEY, value: JSON.stringify(servers) });
}

/** Adds a server, replacing any existing entry for the same origin. */
export async function addServer(server: Server): Promise<Server[]> {
  const servers = await loadServers();
  const next = servers.filter((existing) => existing.origin !== server.origin);
  next.push(server);
  await saveServers(next);
  return next;
}

export async function removeServer(id: string): Promise<Server[]> {
  const next = (await loadServers()).filter((server) => server.id !== id);
  await saveServers(next);
  return next;
}

function isServer(value: unknown): value is Server {
  if (typeof value !== 'object' || value === null) return false;
  const server = value as Record<string, unknown>;
  return (
    typeof server.id === 'string' &&
    typeof server.origin === 'string' &&
    typeof server.name === 'string'
  );
}
