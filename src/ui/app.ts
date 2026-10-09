import type { Server } from '../lib/servers';
import { iconUrl } from '../lib/meta';

export interface ShellHandlers {
  onOpen(server: Server): void;
  onRemove(server: Server): void;
  /** Adds a server; resolves with it, or rejects with a readable message. */
  onAdd(address: string): Promise<Server>;
}

export interface Shell {
  setServers(servers: Server[]): void;
  /** Returns true when it consumed the back gesture (closed a dialog). */
  handleBack(): boolean;
}

interface ElProps {
  class?: string;
  text?: string;
  title?: string;
}

function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: ElProps = {},
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (props.class) node.className = props.class;
  if (props.text !== undefined) node.textContent = props.text;
  if (props.title) node.title = props.title;
  return node;
}

/** First letter of the server name, for a tile whose icon could not load. */
function monogram(name: string): HTMLElement {
  const letter = name.trim().charAt(0).toUpperCase() || '#';
  return el('span', { class: 'tile-icon tile-monogram', text: letter });
}

export function mountShell(root: HTMLElement, handlers: ShellHandlers): Shell {
  const header = el('header', { class: 'shell-header' });
  header.append(el('h1', { class: 'shell-title', text: 'Harmony' }));

  const hint = el('p', {
    class: 'servers-empty',
    text: 'Add the Harmony instances you use. Each opens its own client, so one install holds them all.',
  });

  const list = el('div', { class: 'servers' });
  const main = el('main', { class: 'shell-body' });
  main.append(hint, list);

  root.replaceChildren(header, main);

  // -- Add-server dialog ----------------------------------------------------

  const addForm = el('form', { class: 'sheet-form' });
  const addInput = document.createElement('input');
  addInput.type = 'text';
  addInput.inputMode = 'url';
  addInput.autocapitalize = 'off';
  addInput.autocomplete = 'off';
  addInput.spellcheck = false;
  addInput.placeholder = 'chat.example.com or localhost:8787';
  addInput.className = 'sheet-input';

  const addStatus = el('p', { class: 'sheet-status' });
  const addButtons = el('div', { class: 'sheet-actions' });
  const addCancel = el('button', { class: 'btn', text: 'Cancel' });
  addCancel.type = 'button';
  const addConnect = el('button', { class: 'btn btn-primary', text: 'Connect' });
  addConnect.type = 'submit';
  addButtons.append(addCancel, addConnect);
  addForm.append(addInput, addStatus, addButtons);

  const addDialog = el('dialog', { class: 'sheet' });
  addDialog.append(el('h2', { class: 'sheet-title', text: 'Add a server' }), addForm);

  // -- Server actions sheet -------------------------------------------------

  const actionTitle = el('h2', { class: 'sheet-title' });
  const actionButtons = el('div', { class: 'sheet-actions sheet-actions-column' });
  const openAction = el('button', { class: 'btn', text: 'Open' });
  openAction.type = 'button';
  const removeAction = el('button', { class: 'btn btn-danger', text: 'Remove' });
  removeAction.type = 'button';
  const closeAction = el('button', { class: 'btn', text: 'Cancel' });
  closeAction.type = 'button';
  actionButtons.append(openAction, removeAction, closeAction);

  const actionsDialog = el('dialog', { class: 'sheet' });
  actionsDialog.append(actionTitle, actionButtons);

  root.append(addDialog, actionsDialog);

  let actionServer: Server | null = null;

  // -- Behaviour ------------------------------------------------------------

  function openAdd(): void {
    addInput.value = '';
    addInput.disabled = false;
    addConnect.disabled = false;
    setStatus('', false);
    addDialog.showModal();
    addInput.focus();
  }

  function setStatus(message: string, busy: boolean): void {
    addStatus.textContent = message;
    addStatus.classList.toggle('is-error', !busy && message !== '');
    addInput.disabled = busy;
    addConnect.disabled = busy;
  }

  addCancel.addEventListener('click', () => addDialog.close());
  addDialog.addEventListener('close', () => setStatus('', false));

  addForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const address = addInput.value.trim();
    if (!address) {
      setStatus('Enter an address.', false);
      return;
    }
    setStatus('Connecting…', true);
    handlers
      .onAdd(address)
      .then(() => {
        addDialog.close();
      })
      .catch((error: unknown) => {
        setStatus(error instanceof Error ? error.message : 'Could not reach that address.', false);
      });
  });

  function openActions(server: Server): void {
    actionServer = server;
    actionTitle.textContent = server.name;
    actionsDialog.showModal();
  }

  openAction.addEventListener('click', () => {
    if (actionServer) handlers.onOpen(actionServer);
    actionsDialog.close();
  });
  removeAction.addEventListener('click', () => {
    if (actionServer) handlers.onRemove(actionServer);
    actionsDialog.close();
  });
  closeAction.addEventListener('click', () => actionsDialog.close());

  // -- Tiles ----------------------------------------------------------------

  function buildTile(server: Server): HTMLElement {
    const tile = document.createElement('button');
    tile.type = 'button';
    tile.className = 'tile';
    tile.addEventListener('click', () => handlers.onOpen(server));

    const icon = el('img', { class: 'tile-icon' });
    icon.alt = '';
    icon.loading = 'lazy';
    icon.src = iconUrl(server.origin, server.iconHash);
    icon.addEventListener('error', () => icon.replaceWith(monogram(server.name)));

    const more = document.createElement('button');
    more.type = 'button';
    more.className = 'tile-more';
    more.textContent = '⋮';
    more.setAttribute('aria-label', `${server.name} options`);
    more.addEventListener('click', (event) => {
      event.stopPropagation();
      openActions(server);
    });

    tile.append(icon, el('span', { class: 'tile-name', text: server.name }), more);
    return tile;
  }

  function buildAddTile(): HTMLElement {
    const tile = document.createElement('button');
    tile.type = 'button';
    tile.className = 'tile tile-add';
    tile.addEventListener('click', openAdd);
    const plus = el('span', { class: 'tile-icon tile-plus', text: '+' });
    tile.append(plus, el('span', { class: 'tile-name', text: 'Add server' }));
    return tile;
  }

  return {
    setServers(servers: Server[]): void {
      list.replaceChildren();
      for (const server of servers) list.append(buildTile(server));
      list.append(buildAddTile());
      hint.hidden = servers.length > 0;
    },
    handleBack(): boolean {
      if (addDialog.open) {
        addDialog.close();
        return true;
      }
      if (actionsDialog.open) {
        actionsDialog.close();
        return true;
      }
      return false;
    },
  };
}
