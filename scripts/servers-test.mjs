import test from 'node:test';
import assert from 'node:assert/strict';

import { dropServer, findServer, mergeServer } from '../src/lib/server-model.ts';

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

test('mergeServer appends a new origin', () => {
  const first = server();
  const second = server({ id: 'srv2', origin: 'https://b.example.com', name: 'B' });
  const merged = mergeServer([first], second);
  assert.deepEqual(
    merged.map((entry) => entry.origin),
    ['https://a.example.com', 'https://b.example.com'],
  );
});

test('mergeServer replaces an entry for the same origin instead of duplicating', () => {
  const stale = server({ id: 'old', name: 'Old name' });
  const fresh = server({ id: 'new', name: 'New name' });
  const merged = mergeServer([stale], fresh);
  assert.equal(merged.length, 1);
  assert.equal(merged[0].id, 'new');
  assert.equal(merged[0].name, 'New name');
});

test('mergeServer keeps entries for other origins', () => {
  const other = server({ id: 'srv2', origin: 'https://b.example.com' });
  const merged = mergeServer([other], server({ id: 'srv3', name: 'A again' }));
  assert.deepEqual(
    merged.map((entry) => entry.id),
    ['srv2', 'srv3'],
  );
});

test('dropServer removes only the matching id', () => {
  const kept = server({ id: 'keep', origin: 'https://a.example.com' });
  const dropped = server({ id: 'drop', origin: 'https://b.example.com' });
  const remaining = dropServer([kept, dropped], 'drop');
  assert.deepEqual(
    remaining.map((entry) => entry.id),
    ['keep'],
  );
});

test('findServer prefers the stable instanceId over the origin', () => {
  // The origin entry comes first, but the id is the better match.
  const byOrigin = server({ id: 'by-origin', origin: 'https://key.example.com' });
  const byId = server({ id: 'by-id', origin: 'https://moved.example.com', instanceId: 'key' });
  const found = findServer([byOrigin, byId], { instanceId: 'key', origin: 'https://key.example.com' });
  assert.equal(found?.id, 'by-id');
});

test('findServer falls back to the origin when there is no instanceId', () => {
  const entry = server({ id: 'by-origin', origin: 'https://key.example.com' });
  const found = findServer([entry], { instanceId: null, origin: 'https://key.example.com' });
  assert.equal(found?.id, 'by-origin');
});

test('findServer returns null for an unknown key or an empty key', () => {
  const entry = server({ id: 'by-origin', origin: 'https://key.example.com', instanceId: 'id' });
  assert.equal(findServer([entry], { instanceId: 'nope', origin: 'https://nope.example.com' }), null);
  assert.equal(findServer([entry], {}), null);
});
