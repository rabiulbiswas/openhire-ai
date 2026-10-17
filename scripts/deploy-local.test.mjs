import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const script = fileURLToPath(new URL('./deploy-local.sh', import.meta.url));
const launcher = fileURLToPath(new URL('../run-backend.sh', import.meta.url));

function run(args, overrides = {}, entry = script) {
  const directory = mkdtempSync(join(tmpdir(), 'openhire-deploy-test-'));
  const calls = join(directory, 'calls');
  writeFileSync(calls, '');
  writeFileSync(join(directory, 'docker'), `#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$FAKE_DOCKER_CALLS"
if [[ " $* " == *" build "* && "$FAKE_FAILURE" == build ]]; then exit 1; fi
if [[ " $* " == *" --no-recreate "* && "$FAKE_FAILURE" == infra ]]; then exit 1; fi
if [[ " $* " == *" --force-recreate "* && "$FAKE_FAILURE" == startup ]]; then exit 1; fi
exit 0
`, { mode: 0o700 });
  try {
    const result = spawnSync('bash', [entry, ...args], {
      cwd: directory,
      encoding: 'utf8',
      env: {
        ...process.env, PATH: `${directory}:${process.env.PATH}`,
        FAKE_DOCKER_CALLS: calls, FAKE_FAILURE: '',
        OPENHIRE_DOCKER_PROJECT: 'openhire-test', OPENHIRE_STARTUP_TIMEOUT: '30',
        OPENHIRE_LOG_TAIL: '20', OPENHIRE_FOLLOW_LOGS: '1', ...overrides,
      },
    });
    return { ...result, calls: readFileSync(calls, 'utf8').trim().split('\n').filter(Boolean) };
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

test('help and invalid arguments never contact Docker', () => {
  for (const [args, status] of [[['--help'], 0], [['not-a-service'], 2], [['--bad'], 2]]) {
    const result = run(args);
    assert.equal(result.status, status, result.stderr);
    assert.equal(result.calls.length, 0);
  }
  assert.equal(run([], { OPENHIRE_STARTUP_TIMEOUT: '-1' }).status, 2);
});

test('all applications build before startup and logs, without destructive operations', () => {
  const result = run([]);
  assert.equal(result.status, 0, result.stderr);
  const build = result.calls.findIndex(line => line.includes(' build '));
  const infra = result.calls.findIndex(line => line.includes('--no-recreate'));
  const apps = result.calls.findIndex(line => line.includes('--force-recreate'));
  const logs = result.calls.findIndex(line => line.includes('logs --follow'));
  assert.ok(build >= 0 && infra > build && apps > infra && logs > apps);
  assert.match(result.calls[infra], /keycloak redis kafka tika clamav mailpit$/);
  assert.match(result.calls[infra], /mailpit/);
  assert.match(result.calls[apps], /integration-hub api-gateway$/);
  assert.match(result.calls[logs], /--timestamps --tail 20/);
  for (const line of result.calls) assert.doesNotMatch(line, /\b(down|prune|rmi|rm)\b|--remove-orphans/);
});

test('selected services deduplicate, skip builds/follow when requested and honor project', () => {
  const result = run(['--no-build', '--no-follow', 'job-service', 'job-service']);
  assert.equal(result.status, 0, result.stderr);
  assert.ok(!result.calls.some(line => line.includes(' build ') || line.includes('logs --follow')));
  const apps = result.calls.find(line => line.includes('--force-recreate'));
  assert.match(apps, /--project-name openhire-test/);
  assert.match(apps, /--no-build --pull never/);
  assert.equal((apps.match(/job-service/g) || []).length, 1);
});

test('failures stop deployment or provide diagnostics without following logs', () => {
  for (const failure of ['build', 'infra', 'startup']) {
    const result = run(['job-service'], { FAKE_FAILURE: failure });
    assert.notEqual(result.status, 0);
    assert.ok(!result.calls.some(line => line.includes('logs --follow')));
    if (failure === 'build') assert.ok(!result.calls.some(line => line.includes(' up ')));
    if (failure === 'infra') assert.ok(!result.calls.some(line => line.includes('--force-recreate')));
    if (failure !== 'build') assert.ok(result.calls.some(line => line.includes('logs --no-color')));
  }
});

test('existing launcher forwards deploy arguments without contacting real Docker', () => {
  const result = run(['deploy', '--no-follow', 'api-gateway'], {}, launcher);
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.calls.find(line => line.includes('--force-recreate')), /api-gateway$/);
});
