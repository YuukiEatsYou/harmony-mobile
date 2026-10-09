import test from 'node:test';
import assert from 'node:assert/strict';

import { iconUrl, normalizeAddress } from '../src/lib/meta.ts';

test('assumes http for loopback and private addresses', () => {
  assert.equal(normalizeAddress('localhost:8787'), 'http://localhost:8787');
  assert.equal(normalizeAddress('127.0.0.1'), 'http://127.0.0.1');
  assert.equal(normalizeAddress('192.168.1.50:8787'), 'http://192.168.1.50:8787');
  assert.equal(normalizeAddress('10.0.2.2:8787'), 'http://10.0.2.2:8787');
});

test('assumes https for public addresses', () => {
  assert.equal(normalizeAddress('chat.example.com'), 'https://chat.example.com');
  assert.equal(normalizeAddress('harmony.example.org:8443'), 'https://harmony.example.org:8443');
});

test('respects a typed scheme, including http on a public host', () => {
  assert.equal(normalizeAddress('http://chat.example.com'), 'http://chat.example.com');
  assert.equal(normalizeAddress('https://10.0.0.5:9000'), 'https://10.0.0.5:9000');
});

test('reduces a full url to its origin', () => {
  assert.equal(
    normalizeAddress('https://chat.example.com/some/path?x=1#y'),
    'https://chat.example.com',
  );
});

test('rejects empty, non-http and unreadable input', () => {
  assert.equal(normalizeAddress('   '), null);
  assert.equal(normalizeAddress('ftp://example.com'), null);
  assert.equal(normalizeAddress('not a url'), null);
});

test('builds icon urls with an optional cache-busting hash', () => {
  assert.equal(iconUrl('https://chat.example.com'), 'https://chat.example.com/api/v1/icons/128');
  assert.equal(
    iconUrl('https://chat.example.com', 'abc'),
    'https://chat.example.com/api/v1/icons/128?v=abc',
  );
  assert.equal(
    iconUrl('https://chat.example.com', 'abc', 64),
    'https://chat.example.com/api/v1/icons/64?v=abc',
  );
});
