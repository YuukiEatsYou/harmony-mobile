import './shell.css';
import { Capacitor } from '@capacitor/core';
import { App as CapacitorApp } from '@capacitor/app';
import { mountShell } from './ui/app';
import {
  addServer,
  getLastServer,
  loadServers,
  removeServer,
  serverFromMeta,
  setLastServer,
  type Server,
} from './lib/servers';
import { probeInstance } from './lib/meta';
import { openInstance } from './lib/instance';
import { routeDeepLink } from './lib/deeplink';
import { initNotifications } from './lib/notifications';

const root = document.getElementById('app');
if (!root) throw new Error('Shell mount point #app is missing.');

let servers: Server[] = [];

const shell = mountShell(root, {
  onOpen(server) {
    void openServer(server);
  },
  onRemove(server) {
    void remove(server);
  },
  async onAdd(address) {
    const { origin, meta } = await probeInstance(address);
    const server = serverFromMeta(origin, meta);
    servers = await addServer(server);
    render();
    return server;
  },
});

function render(): void {
  shell.setServers(servers);
}

/** Opens an instance, remembering it so a restart returns straight to it. */
async function openServer(server: Server): Promise<void> {
  await setLastServer(server.origin);
  await openInstance(server.origin);
}

async function remove(server: Server): Promise<void> {
  servers = await removeServer(server.id);
  render();
}

async function boot(): Promise<void> {
  servers = await loadServers();
  render();

  // No-op until push ships upstream (docs/PUSH.md).
  await initNotifications();

  void CapacitorApp.addListener('appUrlOpen', (event) => {
    void routeDeepLink(event.url, servers);
  });

  void CapacitorApp.addListener('backButton', ({ canGoBack }) => {
    if (shell.handleBack()) return;
    if (!canGoBack) void CapacitorApp.exitApp();
  });

  await resumeLastServer();
}

/**
 * Returns to the instance the person was last on, so leaving the app and
 * coming back does not drop them at the server selector. A launch that carries
 * a `harmony://` deep link is left to the `appUrlOpen` handler above; the link
 * knows which instance it wants and should not be pre-empted. Only meaningful
 * on a device: in a browser (`npm run dev`) there is no shell to restore.
 */
async function resumeLastServer(): Promise<void> {
  if (!Capacitor.isNativePlatform()) return;
  if ((await CapacitorApp.getLaunchUrl())?.url) return;
  const origin = await getLastServer();
  if (!origin) return;
  const server = servers.find((candidate) => candidate.origin === origin);
  if (!server) return;
  await openServer(server);
}

void boot();
