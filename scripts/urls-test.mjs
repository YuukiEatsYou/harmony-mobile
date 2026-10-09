import test from 'node:test';
import assert from 'node:assert/strict';

import { buildInstanceUrl, parseDeepLink } from '../src/lib/urls.ts';

test('buildInstanceUrl points at the origin root by default', () => {
  assert.equal(buildInstanceUrl('https://chat.example.com'), 'https://chat.example.com/');
});

test('buildInstanceUrl appends channel and message', () => {
  assert.equal(
    buildInstanceUrl('https://chat.example.com', { channelId: 'c1' }),
    'https://chat.example.com/?channel=c1',
  );
  assert.equal(
    buildInstanceUrl('https://chat.example.com', { channelId: 'c1', messageId: 'm2' }),
    'https://chat.example.com/?channel=c1&message=m2',
  );
});

test('buildInstanceUrl ignores a message with no channel', () => {
  assert.equal(
    buildInstanceUrl('https://chat.example.com', { messageId: 'm2' }),
    'https://chat.example.com/',
  );
});

test('parseDeepLink reads a harmony link', () => {
  assert.deepEqual(
    parseDeepLink(
      'harmony://open?origin=https%3A%2F%2Fchat.example.com&channel=c1&message=m2',
    ),
    {
      origin: 'https://chat.example.com',
      instanceId: null,
      channelId: 'c1',
      messageId: 'm2',
    },
  );
});

test('parseDeepLink accepts an instance id instead of an origin', () => {
  assert.deepEqual(parseDeepLink('harmony://open?instance=abc'), {
    origin: null,
    instanceId: 'abc',
    channelId: null,
    messageId: null,
  });
});

test('parseDeepLink rejects other schemes and unreadable input', () => {
  assert.equal(parseDeepLink('https://chat.example.com'), null);
  assert.equal(parseDeepLink('not a url'), null);
});
