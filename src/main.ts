import './shell.css';
import { App as CapacitorApp } from '@capacitor/app';
import { mountShell } from './ui/app';
import {
  addServer,
  loadServers,
  removeServer,
  serverFromMeta,
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
    void openInstance(server.origin);
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
}

void boot();
