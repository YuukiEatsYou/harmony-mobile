import type { Server } from './servers';
import { openInstance } from './instance';
import { findServer } from './server-model';
import { parseDeepLink } from './urls';

export type { ParsedDeepLink } from './urls';

/** Opens the instance a `harmony://` link points at. Returns false if unknown. */
export async function routeDeepLink(raw: string, servers: Server[]): Promise<boolean> {
  const link = parseDeepLink(raw);
  if (!link) return false;

  const server = findServer(servers, { instanceId: link.instanceId, origin: link.origin });
  if (!server) return false;

  await openInstance(server.origin, {
    channelId: link.channelId ?? undefined,
    messageId: link.messageId ?? undefined,
  });
  return true;
}
