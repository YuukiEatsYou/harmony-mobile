import test from 'node:test';
import assert from 'node:assert/strict';

import {
  coercePushPayload,
  pushTargetKey,
  registerEndpoint,
  registerPushDevice,
  sendPushDevice,
  unregisterEndpoint,
  unregisterPushDevice,
  PUSH_DEVICES_PATH,
} from '../src/lib/push.ts';
import { findServer } from '../src/lib/server-model.ts';

/**
 * The composition `notifications.ts` uses: the payload's key, then the entry it
 * selects. Both halves are pure, so the routing decision is testable with no
 * device and no Capacitor.
 */
function selectPushTarget(payload, servers) {
  return findServer(servers, pushTargetKey(payload));
}

/** A stored instance, with everything but the fields under test filled in. */
function server(overrides = {}) {
  return {
    id: 'srv',
    origin: 'https://a.example.com',
    name: 'A',
    iconHash: null,
    theme: null,
    instanceId: null,
    addedAt: '2026-01-01T00:00:00.000Z',
    ...overrides,
  };
}

/** A full payload, with the routing fields overrideable. */
function payload(overrides = {}) {
  return {
    instanceId: 'inst-a',
    instanceName: 'A',
    iconUrl: null,
    channelId: 'chan-1',
    channelName: 'general',
    messageId: 'msg-9',
    kind: 'mention',
    authorName: 'Zanoba',
    snippet: 'hey',
    ...overrides,
  };
}

test('selectPushTarget routes a payload by its stable instanceId', () => {
  const a = server({ id: 'a', origin: 'https://a.example.com', instanceId: 'inst-a' });
  const b = server({ id: 'b', origin: 'https://b.example.com', instanceId: 'inst-b' });
  const found = selectPushTarget(payload({ instanceId: 'inst-b' }), [a, b]);
  assert.equal(found?.id, 'b');
  assert.equal(found?.origin, 'https://b.example.com');
});

test('selectPushTarget falls back to the origin when the payload key is an origin', () => {
  // Today's servers send no instanceId, so the sender puts the origin in the key.
  const entry = server({ id: 'by-origin', origin: 'https://key.example.com' });
  const found = selectPushTarget(payload({ instanceId: 'https://key.example.com' }), [entry]);
  assert.equal(found?.id, 'by-origin');
});

test('selectPushTarget prefers the instanceId over an origin match', () => {
  const byOrigin = server({ id: 'by-origin', origin: 'https://key.example.com' });
  const byId = server({
    id: 'by-id',
    origin: 'https://moved.example.com',
    instanceId: 'https://key.example.com',
  });
  const found = selectPushTarget(payload({ instanceId: 'https://key.example.com' }), [
    byOrigin,
    byId,
  ]);
  assert.equal(found?.id, 'by-id');
});

test('selectPushTarget returns null for an instance this install does not have', () => {
  const a = server({ id: 'a', instanceId: 'inst-a' });
  assert.equal(selectPushTarget(payload({ instanceId: 'inst-gone' }), [a]), null);
  assert.equal(selectPushTarget(payload({ instanceId: 'inst-gone' }), []), null);
});

test('a delivered payload string reaches the right server', () => {
  // The end-to-end decision the router makes, minus the native open: the wire
  // JSON the provider delivered -> the selected stored instance.
  const wire = JSON.stringify(
    payload({ instanceId: 'inst-b', channelId: 'chan-2', messageId: 'msg-4' }),
  );
  const servers = [
    server({ id: 'a', instanceId: 'inst-a' }),
    server({ id: 'b', origin: 'https://b.example.com', instanceId: 'inst-b' }),
  ];
  const parsed = coercePushPayload(JSON.parse(wire));
  assert.ok(parsed);
  const target = selectPushTarget(parsed, servers);
  assert.equal(target?.id, 'b');
  assert.equal(parsed.channelId, 'chan-2');
  assert.equal(parsed.messageId, 'msg-4');
});

test('coercePushPayload drops a payload that cannot be routed', () => {
  assert.equal(coercePushPayload(null), null);
  assert.equal(coercePushPayload('not an object'), null);
  assert.equal(coercePushPayload({ channelId: 'c', messageId: 'm' }), null);
  assert.equal(coercePushPayload({ instanceId: 'i', messageId: 'm' }), null);
  assert.equal(coercePushPayload({ instanceId: 'i', channelId: 'c' }), null);
  // An empty key is not a key.
  assert.equal(coercePushPayload({ instanceId: '', channelId: 'c', messageId: 'm' }), null);
});

test('coercePushPayload defaults the display-only fields', () => {
  const parsed = coercePushPayload({ instanceId: 'i', channelId: 'c', messageId: 'm' });
  assert.ok(parsed);
  assert.equal(parsed.kind, 'mention');
  assert.equal(parsed.instanceName, 'i');
  assert.equal(parsed.iconUrl, null);
  assert.equal(parsed.snippet, null);
  // A reply is kept; anything else that is not 'reply' reads as a mention.
  assert.equal(coercePushPayload({ instanceId: 'i', channelId: 'c', messageId: 'm', kind: 'reply' })?.kind, 'reply');
  assert.equal(coercePushPayload({ instanceId: 'i', channelId: 'c', messageId: 'm', kind: 'bogus' })?.kind, 'mention');
});

test('registerPushDevice builds the docs/PUSH.md §3 PUT', () => {
  const request = registerPushDevice('https://dist.example/endpoint/abc');
  assert.equal(request.method, 'PUT');
  assert.equal(request.path, PUSH_DEVICES_PATH);
  assert.deepEqual(request.body, {
    endpoint: 'https://dist.example/endpoint/abc',
    platform: 'android',
  });
});

test('unregisterPushDevice builds the removal, without a platform', () => {
  const request = unregisterPushDevice('https://dist.example/endpoint/abc');
  assert.equal(request.method, 'DELETE');
  assert.equal(request.path, PUSH_DEVICES_PATH);
  assert.deepEqual(request.body, { endpoint: 'https://dist.example/endpoint/abc' });
});

test('registerEndpoint registers the one endpoint with every instance', async () => {
  const servers = [
    server({ id: 'a', origin: 'https://a.example.com' }),
    server({ id: 'b', origin: 'https://b.example.com' }),
  ];
  const seen = [];
  const results = await registerEndpoint('ep-1', servers, async (origin, request) => {
    seen.push([origin, request.method, request.body.endpoint]);
    return { ok: true, status: 200 };
  });
  assert.deepEqual(seen, [
    ['https://a.example.com', 'PUT', 'ep-1'],
    ['https://b.example.com', 'PUT', 'ep-1'],
  ]);
  assert.deepEqual(results, [
    { origin: 'https://a.example.com', ok: true, status: 200 },
    { origin: 'https://b.example.com', ok: true, status: 200 },
  ]);
});

test('unregisterEndpoint removes the endpoint from every instance', async () => {
  const servers = [
    server({ id: 'a', origin: 'https://a.example.com' }),
    server({ id: 'b', origin: 'https://b.example.com' }),
  ];
  const methods = [];
  await unregisterEndpoint('ep-1', servers, async (_origin, request) => {
    methods.push(request.method);
    return { ok: true, status: 200 };
  });
  assert.deepEqual(methods, ['DELETE', 'DELETE']);
});

test('a failure on one instance does not stop the others', async () => {
  const servers = [
    server({ id: 'a', origin: 'https://a.example.com' }),
    server({ id: 'b', origin: 'https://b.example.com' }),
    server({ id: 'c', origin: 'https://c.example.com' }),
  ];
  const results = await sendPushDevice(registerPushDevice('ep-1'), servers, async (origin) => {
    if (origin === 'https://b.example.com') return { ok: false, status: 401 };
    if (origin === 'https://c.example.com') throw new Error('offline');
    return { ok: true, status: 200 };
  });
  assert.deepEqual(results, [
    { origin: 'https://a.example.com', ok: true, status: 200 },
    { origin: 'https://b.example.com', ok: false, status: 401 },
    { origin: 'https://c.example.com', ok: false },
  ]);
});
