import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, unlink, rmdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { Readable } from 'node:stream';
import { encryptArchive, verifyArchive, restoreArguments, databaseName, keyFrom } from '../maintenance/mongo-recovery.mjs';

test('backup authenticates, rejects tampering/wrong keys, and never overwrites', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'openhire-recovery-test-'));
  const path = join(dir, 'test.oha'); const key = randomBytes(32);
  try {
    await encryptArchive(Readable.from(['private-test-data']), path, 'oh_integration', key);
    assert.equal((await verifyArchive(path, key)).database, 'oh_integration');
    assert.equal((await readFile(path)).includes(Buffer.from('private-test-data')), false);
    await assert.rejects(encryptArchive(Readable.from(['other']), path, 'oh_integration', key), /EEXIST/);
    await assert.rejects(verifyArchive(path, randomBytes(32)));
    const bytes = await readFile(path); bytes[bytes.length - 17] ^= 1; await writeFile(path, bytes);
    await assert.rejects(verifyArchive(path, key));
  } finally { await unlink(path).catch(() => {}); await rmdir(dir); }
});
test('failed input never publishes a partial backup', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'openhire-recovery-test-')); const path = join(dir, 'partial.oha');
  async function* failure() { yield Buffer.from('partial'); throw new Error('dump failed'); }
  try {
    await assert.rejects(encryptArchive(failure(), path, 'oh_integration', randomBytes(32)), /dump failed/);
    await assert.rejects(readFile(path), /ENOENT/);
  } finally { await rmdir(dir); }
});
test('restore is restricted to an isolated namespace without drop', () => {
  assert.throws(() => databaseName('admin'));
  assert.throws(() => databaseName('oh_*'));
  assert.throws(() => restoreArguments('config', 'oh_integration', 'oh_identity'));
  const args = restoreArguments('config', 'oh_integration', `oh_restore_${'a'.repeat(32)}`);
  assert.ok(args.includes('--stopOnError')); assert.ok(!args.includes('--drop'));
  assert.throws(() => keyFrom('password'));
});
