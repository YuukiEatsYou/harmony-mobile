import {
  clearDiagnostics,
  diagnosticsReport,
  openBatterySettings,
  shareDiagnostics,
  stopVoice,
  testVoice,
  voiceJoin,
  voiceLeave,
} from '../lib/diagnostics';

/**
 * The on-device call diagnostics overlay.
 *
 * Reached by long-pressing the shell title (see src/ui/app.ts). It is a support
 * affordance rather than a normal screen: it shows whether the call foreground
 * service is actually running and lets a person share the report from the phone,
 * which is the only way to get evidence off a device that has no adb.
 */

let overlay: HTMLElement | null = null;

export function openDiagnostics(): void {
  if (overlay) return;

  const output = document.createElement('pre');
  output.className = 'diagnostics-output';
  output.textContent = 'Loading…';

  const card = document.createElement('div');
  card.className = 'diagnostics-card';

  const title = document.createElement('h2');
  title.className = 'sheet-title';
  title.textContent = 'Call diagnostics';

  const hint = document.createElement('p');
  hint.className = 'diagnostics-hint';
  hint.textContent =
    'Live state of the call foreground service. If a call drops in the background, tap Share and send this.';

  const actions = document.createElement('div');
  actions.className = 'diagnostics-actions';

  const reload = async (): Promise<void> => {
    output.textContent = 'Loading…';
    try {
      output.textContent = await diagnosticsReport();
    } catch (error) {
      output.textContent = error instanceof Error ? error.message : String(error);
    }
  };

  const battery = button('Battery settings', () => void openBatterySettings());
  const voiceTest = button('Voice test', async () => {
    await testVoice();
    await reload();
  });
  const voiceJoinButton = button('Voice join', async () => {
    await voiceJoin();
    await reload();
  });
  const voiceLeaveButton = button('Voice leave', () => void voiceLeave());
  const voiceStop = button('Voice stop', () => void stopVoice());
  const clear = button('Clear log', async () => {
    await clearDiagnostics();
    await reload();
  });
  const refresh = button('Refresh', () => void reload());
  const share = button('Share', () => void shareDiagnostics(output.textContent ?? ''));
  const close = button('Close', () => closeDiagnostics());
  close.classList.add('btn-primary');

  actions.append(
    battery,
    voiceTest,
    voiceJoinButton,
    voiceLeaveButton,
    voiceStop,
    clear,
    refresh,
    share,
    close,
  );
  card.append(title, hint, output, actions);

  const root = document.createElement('div');
  root.className = 'diagnostics';
  root.setAttribute('role', 'dialog');
  root.setAttribute('aria-modal', 'true');
  root.append(card);
  root.addEventListener('click', (event) => {
    if (event.target === root) closeDiagnostics();
  });

  overlay = root;
  document.body.append(root);
  document.addEventListener('keydown', onKeydown);

  void reload();
}

/** Closes the overlay if it is open; returns whether it consumed the action. */
export function closeDiagnostics(): boolean {
  if (!overlay) return false;
  overlay.remove();
  overlay = null;
  document.removeEventListener('keydown', onKeydown);
  return true;
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') closeDiagnostics();
}

function button(label: string, onClick: () => void): HTMLButtonElement {
  const node = document.createElement('button');
  node.type = 'button';
  node.className = 'btn';
  node.textContent = label;
  node.addEventListener('click', onClick);
  return node;
}
