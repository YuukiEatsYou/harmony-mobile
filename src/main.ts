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
import { initNotifications, registerPushEndpoint, unregisterPushEndpoint } from './lib/notifications';
import { closeDiagnostics, openDiagnostics } from './ui/diagnostics';

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
    // The endpoint is per install, so a newly added instance is registered too
    // (docs/PUSH.md §3); a no-op until a push provider is wired.
    void registerPushEndpoint([server]);
    return server;
  },
  onDiagnostics() {
    openDiagnostics();
  },
});

function render(): void {
  shell.setServers(servers);
}

async function remove(server: Server): Promise<void> {
  // Remove the install's push endpoint from the instance first, while its
  // cookies still exist — the one sign-out the shell can see (F1).
  void unregisterPushEndpoint([server]);
  servers = await removeServer(server.id);
  render();
}

async function boot(): Promise<void> {
  servers = await loadServers();
  render();

  // Wires the push provider (docs/PUSH.md); a no-op on the web preview and
  // until a distributor supplies an endpoint.
  await initNotifications(() => servers);

  void CapacitorApp.addListener('appUrlOpen', (event) => {
    void routeDeepLink(event.url, servers);
  });

  // A session can appear after boot (signing in inside an instance webview), and
  // the provider's endpoint can arrive late, so re-register on every resume; the
  // already-registered origins are skipped, and a 401 is retried next time (F3).
  void CapacitorApp.addListener('resume', () => {
    void registerPushEndpoint(servers);
  });

  void CapacitorApp.addListener('backButton', ({ canGoBack }) => {
    if (closeDiagnostics()) return;
    if (shell.handleBack()) return;
    if (!canGoBack) void CapacitorApp.exitApp();
  });
}

void boot();
