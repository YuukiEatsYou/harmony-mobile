import test from 'node:test';
import assert from 'node:assert/strict';

import { iconUrl, normalizeAddress, classifyHttpFailure, classifyNetworkFailure, fetchMeta, ProbeError } from '../src/lib/meta.ts';

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

// -- Probe failure states ----------------------------------------------------

test('classifyNetworkFailure reports a timeout when the probe gave up', () => {
  const error = classifyNetworkFailure(
    'https://chat.example.com',
    new TypeError('Failed to fetch'),
    true,
  );
  assert.ok(error instanceof ProbeError);
  assert.equal(error.kind, 'timeout');
  assert.match(error.message, /Timed out/);
});

test('classifyNetworkFailure reports an aborted fetch as a timeout', () => {
  const aborted = new DOMException('aborted', 'AbortError');
  assert.equal(classifyNetworkFailure('https://chat.example.com', aborted).kind, 'timeout');
});

test('classifyNetworkFailure reports everything else as unreachable', () => {
  // A browser collapses DNS, TLS and offline into one rejection; it is not
  // distinguishable, so it is all "unreachable".
  const error = classifyNetworkFailure('https://chat.example.com', new TypeError('Failed to fetch'));
  assert.equal(error.kind, 'unreachable');
  assert.match(error.message, /Could not reach/);
});

test('classifyHttpFailure separates the status classes', () => {
  assert.equal(classifyHttpFailure('http://h:1', 404).kind, 'http');
  assert.match(classifyHttpFailure('http://h:1', 404).message, /no \/api\/v1\/meta/);
  assert.equal(classifyHttpFailure('http://h:1', 403).kind, 'http');
  assert.equal(classifyHttpFailure('http://h:1', 429).kind, 'http');
  assert.equal(classifyHttpFailure('http://h:1', 500).kind, 'http');
  assert.equal(classifyHttpFailure('http://h:1', 504).kind, 'timeout');
  assert.equal(classifyHttpFailure('http://h:1', 302).kind, 'redirected');
});

test('classifyHttpFailure calls a redirect off the instance what it is', () => {
  const error = classifyHttpFailure('https://chat.example.com', 200, 'https://portal.example.net/login');
  assert.equal(error.kind, 'redirected');
  assert.match(error.message, /portal\.example\.net/);
});

test('classifyHttpFailure does not treat a same-host final url as a redirect', () => {
  const error = classifyHttpFailure('https://chat.example.com', 500, 'https://chat.example.com/login');
  assert.equal(error.kind, 'http');
});

test('fetchMeta gives up after its timeout and reports a timeout', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = (_input, init) =>
    new Promise((_resolve, reject) => {
      // Like a real fetch: it only settles when the signal aborts.
      init?.signal?.addEventListener('abort', () =>
        reject(new DOMException('aborted', 'AbortError')),
      );
    });
  try {
    const error = await fetchMeta('https://slow.example.com', { timeoutMs: 20 }).then(
      () => null,
      (caught) => caught,
    );
    assert.ok(error instanceof ProbeError);
    assert.equal(error.kind, 'timeout');
  } finally {
    globalThis.fetch = original;
  }
});
