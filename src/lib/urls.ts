/**
 * Pure URL helpers, kept free of any Capacitor import so they can be tested in
 * plain Node. See scripts/urls-test.mjs.
 */

/** Where in an instance to land, for notifications and `harmony://` links. */
export interface DeepLink {
  channelId?: string;
  messageId?: string;
}

/**
 * The instance's own client is loaded from the instance's origin — we never
 * bundle or script into it. Deep-link parameters are appended when present;
 * today's client ignores them (it has no URL routing yet), and this starts
 * working once upstream reads them. See UPSTREAM_CHANGES.md.
 */
export function buildInstanceUrl(origin: string, deepLink?: DeepLink): string {
  const url = new URL('/', origin);
  if (deepLink?.channelId) {
    url.searchParams.set('channel', deepLink.channelId);
    if (deepLink.messageId) url.searchParams.set('message', deepLink.messageId);
  }
  return url.toString();
}

/**
 * A `harmony://` link into an instance:
 *
 *   harmony://open?origin=https%3A%2F%2Fchat.example.com&channel=<id>&message=<id>
 *
 * `instance` (the docs/PUSH.md §1 id) may be used instead of `origin`.
 */
export interface ParsedDeepLink {
  origin: string | null;
  instanceId: string | null;
  channelId: string | null;
  messageId: string | null;
}

export function parseDeepLink(raw: string): ParsedDeepLink | null {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== 'harmony:') return null;

  const params = url.searchParams;
  return {
    origin: params.get('origin'),
    instanceId: params.get('instance'),
    channelId: params.get('channel'),
    messageId: params.get('message'),
  };
}
