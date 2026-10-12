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

/**
 * Why a `meta` probe failed. The message is what the add-server dialog shows;
 * the kind lets the shell (and the tests) tell a timeout from a wrong address
 * without reading prose — a failure state, not just the happy path.
 */
export type ProbeFailureKind =
  | 'invalid-address'
  | 'timeout'
  | 'unreachable'
  | 'redirected'
  | 'http'
  | 'not-json'
  | 'not-harmony';

/** A readable, categorised probe failure. Extends `Error`, so `error.message` still works. */
export class ProbeError extends Error {
  readonly kind: ProbeFailureKind;
  readonly origin: string;
  readonly status?: number;

  constructor(kind: ProbeFailureKind, origin: string, message: string, status?: number) {
    super(message);
    this.name = 'ProbeError';
    this.kind = kind;
    this.origin = origin;
    if (status !== undefined) this.status = status;
  }
}

/** How long a probe waits for a response before it is called a timeout. */
export const DEFAULT_PROBE_TIMEOUT_MS = 8000;

export interface ProbeOptions {
  /** Overrides the timeout; mainly for tests. */
  timeoutMs?: number;
  /** An external abort, e.g. the add-server dialog closing. */
  signal?: AbortSignal;
}

function sameHost(a: string, b: string): boolean {
  try {
    return new URL(a).host === new URL(b).host;
  } catch {
    return false;
  }
}

/**
 * Maps a rejected `fetch` to a readable failure. A browser — and the Android
 * WebView — collapses DNS failure, TLS failure and "no network" into one
 * rejected promise, so they share the `unreachable` kind; only a timeout is
 * distinguishable, and `timedOut` says so.
 */
export function classifyNetworkFailure(
  origin: string,
  error: unknown,
  timedOut = false,
): ProbeError {
  const name =
    error && typeof error === 'object' && 'name' in error
      ? String((error as { name: unknown }).name)
      : '';
  if (timedOut || name === 'TimeoutError' || name === 'AbortError') {
    return new ProbeError(
      'timeout',
      origin,
      `Timed out reaching ${origin}. Check the address or try again.`,
    );
  }
  return new ProbeError(
    'unreachable',
    origin,
    `Could not reach ${origin}. Check the address and your connection.`,
  );
}

/**
 * Maps a non-OK `meta` response, or a redirect that landed off the instance, to
 * a readable failure. HTTP status is classified rather than printed raw so a
 * person can act on it.
 */
export function classifyHttpFailure(
  origin: string,
  status: number,
  finalUrl?: string,
): ProbeError {
  // A redirect to another host means we are no longer talking to the instance
  // that was asked for (a captive portal, a moved install, a login wall).
  if (finalUrl && !sameHost(origin, finalUrl)) {
    let host = finalUrl;
    try {
      host = new URL(finalUrl).host;
    } catch {
      // Keep the raw url when it will not parse.
    }
    return new ProbeError(
      'redirected',
      origin,
      `${origin} redirected to ${host}, which is not a Harmony instance.`,
      status,
    );
  }
  if (status === 404) {
    return new ProbeError(
      'http',
      origin,
      `${origin} has no /api/v1/meta; it is not a Harmony instance.`,
      status,
    );
  }
  if (status === 401 || status === 403) {
    return new ProbeError(
      'http',
      origin,
      `${origin} refused the request (${status}). Check the address, or sign in from the app.`,
      status,
    );
  }
  if (status === 408 || status === 504) {
    return new ProbeError('timeout', origin, `${origin} timed out answering (${status}). Try again.`, status);
  }
  if (status === 429) {
    return new ProbeError(
      'http',
      origin,
      `${origin} is rate-limiting requests (429). Try again shortly.`,
      status,
    );
  }
  if (status >= 300 && status < 400) {
    return new ProbeError(
      'redirected',
      origin,
      `${origin} redirected the request (${status}); it is not a Harmony instance.`,
      status,
    );
  }
  if (status >= 500) {
    return new ProbeError(
      'http',
      origin,
      `${origin} is having trouble (${status}). Try again shortly.`,
      status,
    );
  }
  return new ProbeError(
    'http',
    origin,
    `${origin} answered ${status}, which is not a Harmony instance.`,
    status,
  );
}

/**
 * Fetches and validates an instance's `meta`. Throws a `ProbeError` whose
 * `message` is readable and whose `kind` categorises the failure: invalid
 * address (from `probeInstance`), timeout, unreachable host, a redirect off the
 * instance, an HTTP error, a non-JSON body, or a body that is not a Harmony
 * `meta`. It always stops after `timeoutMs` so a silent host cannot hang the
 * add-server dialog.
 */
export async function fetchMeta(
  origin: string,
  options: ProbeOptions = {},
): Promise<InstanceMeta> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_PROBE_TIMEOUT_MS;
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  const external = options.signal;
  const onExternalAbort = (): void => controller.abort();
  if (external) {
    if (external.aborted) controller.abort();
    else external.addEventListener('abort', onExternalAbort, { once: true });
  }

  try {
    let response: Response;
    try {
      response = await fetch(`${origin}/api/v1/meta`, {
        headers: { Accept: 'application/json' },
        signal: controller.signal,
      });
    } catch (error) {
      throw classifyNetworkFailure(origin, error, timedOut);
    }

    if (!response.ok) {
      throw classifyHttpFailure(origin, response.status, response.redirected ? response.url : undefined);
    }
    // A 200 from somewhere else — a captive portal, a login page on another
    // host — is not this instance, however healthy the response looks.
    if (response.redirected && !sameHost(origin, response.url)) {
      throw classifyHttpFailure(origin, response.status, response.url);
    }

    let data: unknown;
    try {
      data = await response.json();
    } catch {
      throw new ProbeError('not-json', origin, `${origin} did not return JSON.`);
    }

    if (!isInstanceMeta(data)) {
      throw new ProbeError(
        'not-harmony',
        origin,
        'That address is reachable but does not look like a Harmony instance.',
      );
    }
    return data;
  } finally {
    clearTimeout(timer);
    external?.removeEventListener('abort', onExternalAbort);
  }
}

/** Normalizes an address and fetches its `meta` in one step. */
export async function probeInstance(
  raw: string,
): Promise<{ origin: string; meta: InstanceMeta }> {
  const origin = normalizeAddress(raw);
  if (!origin) {
    throw new ProbeError(
      'invalid-address',
      raw,
      'That does not look like an address. Try example.com or localhost:8787.',
    );
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
