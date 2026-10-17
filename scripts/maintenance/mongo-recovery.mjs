#!/usr/bin/env node
// Encrypted single-database backups and isolated restore rehearsals. Never restores over a live database.
import { createCipheriv, createDecipheriv, randomBytes, randomUUID } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { open, stat, mkdtemp, link, unlink, rmdir } from 'node:fs/promises';
import { dirname, resolve, join } from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Writable } from 'node:stream';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const magic = Buffer.from('OHBACK01');
const discard = () => new Writable({ write(_chunk, _encoding, done) { done(); } });
export function keyFrom(value) {
  if (!value || !/^[A-Za-z0-9+/]{43}=$/.test(value)) throw new Error('RECOVERY_ENCRYPTION_KEY must be a base64 32-byte key');
  const key = Buffer.from(value, 'base64');
  if (key.length !== 32) throw new Error('Invalid recovery key');
  return key;
}
export function databaseName(value) {
  if (!/^(?:oh_|openhire_)[a-z][a-z0-9_]{0,63}$/.test(value ?? '') || value.startsWith('oh_restore_'))
    throw new Error('Select one explicit oh_* or openhire_* application database, not a restore database');
  return value;
}
export async function privateConfig(path) {
  const info = await stat(path);
  if (!info.isFile() || (info.mode & 0o077)) throw new Error('Mongo tool config must be a private file (chmod 600)');
  // mongodump/mongorestore read URI/password from this file, never from the process arguments or logs.
  return resolve(path);
}
export async function encryptArchive(source, destination, database, key) {
  databaseName(database);
  const header = Buffer.from(JSON.stringify({ version: 1, database, createdAt: new Date().toISOString() }));
  const length = Buffer.alloc(4); length.writeUInt32BE(header.length);
  const iv = randomBytes(12);
  const prefix = Buffer.concat([magic, length, header, iv]);
  const temporary = await mkdtemp(join(dirname(resolve(destination)), '.openhire-backup-'));
  const file = join(temporary, 'archive');
  try {
    const handle = await open(file, 'wx', 0o600);
    await handle.writeFile(prefix); await handle.close();
    const cipher = createCipheriv('aes-256-gcm', key, iv); cipher.setAAD(prefix);
    await pipeline(source, cipher, createWriteStream(file, { flags: 'a', mode: 0o600 }));
    const append = await open(file, 'a');
    await append.writeFile(cipher.getAuthTag()); await append.sync(); await append.close();
    // Exclusive publication: existing backups are never overwritten.
    await link(file, resolve(destination));
  } finally {
    await unlink(file).catch(() => {}); await rmdir(temporary);
  }
}
async function envelope(file, key) {
  const handle = await open(file, 'r');
  try {
    const size = (await handle.stat()).size;
    const start = Buffer.alloc(12); await handle.read(start, 0, 12, 0);
    if (!start.subarray(0, 8).equals(magic)) throw new Error('Not an OpenHire encrypted backup');
    const length = start.readUInt32BE(8);
    if (length < 2 || length > 4096 || size < 12 + length + 12 + 16) throw new Error('Invalid backup envelope');
    const prefix = Buffer.alloc(12 + length + 12); await handle.read(prefix, 0, prefix.length, 0);
    const metadata = JSON.parse(prefix.subarray(12, 12 + length).toString());
    if (metadata.version !== 1) throw new Error('Unsupported backup version');
    databaseName(metadata.database);
    const tag = Buffer.alloc(16); await handle.read(tag, 0, 16, size - 16);
    const decipher = createDecipheriv('aes-256-gcm', key, prefix.subarray(-12));
    decipher.setAAD(prefix); decipher.setAuthTag(tag);
    return { metadata, decipher, source: createReadStream(file, { start: prefix.length, end: size - 17 }) };
  } finally { await handle.close(); }
}
export async function verifyArchive(file, key) {
  const { metadata, source, decipher } = await envelope(file, key);
  await pipeline(source, decipher, discard());
  return metadata;
}
export function restoreArguments(config, database, target) {
  databaseName(database);
  if (!/^oh_restore_[a-f0-9]{32}$/.test(target)) throw new Error('Restore target must be a newly generated isolated database');
  return ['--config', config, '--archive', '--gzip', '--stopOnError', '--nsInclude', `${database}.*`, '--nsFrom', `${database}.*`, '--nsTo', `${target}.*`];
}
function processTool(name, args, input = false) {
  const child = spawn(name, args, { stdio: [input ? 'pipe' : 'ignore', input ? 'ignore' : 'pipe', 'pipe'] });
  // Tool stderr can contain connection strings or document values. Do not echo it.
  child.stderr.resume();
  const done = new Promise((resolveDone, reject) => {
    child.on('error', () => reject(new Error(`${name} could not start`)));
    child.on('close', code => code === 0 ? resolveDone() : reject(new Error(`${name} failed; inspect server-side logs securely`)));
  });
  done.catch(() => {});
  return { child, done };
}
export async function main(args) {
  const [operation, file, database, ...rest] = args;
  const key = keyFrom(process.env.RECOVERY_ENCRYPTION_KEY);
  if (!file) throw new Error('Usage: mongo-recovery.mjs backup FILE DATABASE | verify FILE | rehearse FILE [--execute]');
  if (operation === 'verify') {
    if (database || rest.length) throw new Error('Unexpected arguments');
    console.log(JSON.stringify({ status: 'AUTHENTICATED', ...await verifyArchive(file, key) })); return;
  }
  if (operation === 'backup') {
    if (rest.length) throw new Error('Unexpected arguments');
    databaseName(database);
    const config = await privateConfig(process.env.RECOVERY_MONGO_CONFIG ?? '');
    const { child, done } = processTool('mongodump', ['--config', config, '--db', database, '--archive', '--gzip']);
    // Gate EOF on successful mongodump completion, so a partial dump is never published.
    async function* checked() { for await (const chunk of child.stdout) yield chunk; await done; }
    try { await encryptArchive(checked(), file, database, key); }
    catch (error) { child.kill(); throw error; }
    console.log('Encrypted backup created. Keep the encryption key separately in your secret manager.'); return;
  }
  if (operation === 'rehearse') {
    if ((database && database !== '--execute') || rest.length) throw new Error('Only --execute is accepted');
    const metadata = await verifyArchive(file, key); // Full authentication before any database writes.
    const target = `oh_restore_${randomUUID().replaceAll('-', '')}`;
    if (database !== '--execute') {
      console.log(JSON.stringify({ status: 'DRY_RUN', source: metadata.database, target, note: 'No database writes. Use --execute with an isolated recovery Mongo config.' })); return;
    }
    const config = await privateConfig(process.env.RECOVERY_MONGO_CONFIG ?? '');
    const { child, done } = processTool('mongorestore', restoreArguments(config, metadata.database, target), true);
    console.log(`Restore target: ${target}. No existing application database will be replaced.`);
    try {
      const { source, decipher } = await envelope(file, key);
      await Promise.all([pipeline(source, decipher, child.stdin), done]);
    } catch (error) { child.kill(); throw new Error(`Restore failed; inspect the partial isolated database ${target}. No cleanup was performed.`, { cause: error }); }
    console.log(JSON.stringify({ status: 'RESTORED_FOR_REVIEW', target, note: 'Run application-specific checks. No automatic promotion or deletion.' })); return;
  }
  throw new Error('Unknown recovery operation');
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
