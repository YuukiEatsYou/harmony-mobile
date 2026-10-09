/**
 * Reading an address the user typed and confirming it is a Harmony instance,
 * mirroring the desktop client's add-server flow.
 */

export interface InstanceTheme {
  background: string | null;
  accent: string | null;
}

/** The public fields of `GET /api/v1/meta` this client uses. */
export interface InstanceMeta {
  name: string;
  apiVersion: string;
  requireInvite?: boolean;
  theme?: InstanceTheme | null;
  iconHash?: string | null;
  /**
   * A stable instance id, proposed upstream in docs/PUSH.md §1. Absent on
   * today's servers, so it is optional here and the origin is the fallback key.
   */
  instanceId?: string;
}

const LOOPBACK = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);

function isPrivateHost(hostname: string): boolean {
  const host = hostname.toLowerCase();
  if (LOOPBACK.has(host) || host.endsWith('.localhost') || host.endsWith('.local')) {
    return true;
  }
  if (/^10\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(host)) return true;
  if (/^192\.168\.\d{1,3}\.\d{1,3}$/.test(host)) return true;
  if (/^172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}$/.test(host)) return true;
  return false;
}

/**
 * Turns what a person types into an origin, or null if it cannot be read.
 *
 * A typed scheme is respected. Without one, loopback and private addresses are
 * assumed to be `http` (a local test instance), everything else `https` — the
 * same rule the desktop client uses.
 */
export function normalizeAddress(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;

  let url: URL;
  if (/^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)) {
    try {
      url = new URL(trimmed);
    } catch {
      return null;
    }
  } else {
    let probe: URL;
    try {
      // Any scheme works for the probe; we only need the parsed hostname.
      probe = new URL(`http://${trimmed}`);
    } catch {
      return null;
    }
    const scheme = isPrivateHost(probe.hostname) ? 'http' : 'https';
    try {
      url = new URL(`${scheme}://${trimmed}`);
    } catch {
      return null;
    }
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
  if (!url.hostname) return null;
  // The origin only: Harmony is served at its own root, with no path prefix.
  return url.origin;
}

/** Fetches and validates an instance's `meta`. Throws a readable Error on failure. */
export async function fetchMeta(origin: string): Promise<InstanceMeta> {
  let response: Response;
  try {
    response = await fetch(`${origin}/api/v1/meta`, {
      headers: { Accept: 'application/json' },
    });
  } catch {
    throw new Error(`Could not reach ${origin}.`);
  }

  if (!response.ok) {
    throw new Error(`${origin} answered ${response.status}, which is not a Harmony instance.`);
  }

  let data: unknown;
  try {
    data = await response.json();
  } catch {
    throw new Error(`${origin} did not return JSON.`);
  }

  if (!isInstanceMeta(data)) {
    throw new Error('That address is reachable but does not look like a Harmony instance.');
  }
  return data;
}

/** Normalizes an address and fetches its `meta` in one step. */
export async function probeInstance(
  raw: string,
): Promise<{ origin: string; meta: InstanceMeta }> {
  const origin = normalizeAddress(raw);
  if (!origin) {
    throw new Error('That does not look like an address. Try example.com or localhost:8787.');
  }
  const meta = await fetchMeta(origin);
  return { origin, meta };
}

/**
 * The instance icon. `GET /api/v1/icons/:size` returns the built-in default
 * when no icon is uploaded, so this is safe to use unconditionally; the hash is
 * appended so a replaced icon is not served from cache.
 */
export function iconUrl(origin: string, iconHash?: string | null, size = 128): string {
  const base = `${origin}/api/v1/icons/${size}`;
  return iconHash ? `${base}?v=${encodeURIComponent(iconHash)}` : base;
}

function isInstanceMeta(value: unknown): value is InstanceMeta {
  if (typeof value !== 'object' || value === null) return false;
  const meta = value as Record<string, unknown>;
  return typeof meta.name === 'string' && typeof meta.apiVersion === 'string';
}
