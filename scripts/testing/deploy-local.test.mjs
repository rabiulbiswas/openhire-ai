import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const script = fileURLToPath(new URL('../deployment/deploy-local.sh', import.meta.url));
const launcher = fileURLToPath(new URL('../../run-backend.sh', import.meta.url));

function run(args, overrides = {}, entry = script) {
  const directory = mkdtempSync(join(tmpdir(), 'openhire-deploy-test-'));
  const calls = join(directory, 'calls');
  writeFileSync(calls, '');
  writeFileSync(join(directory, 'docker'), `#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$FAKE_DOCKER_CALLS"
if [[ "$FAKE_PORTAL" == 1 ]]; then
  if [[ "$1" == ps && " $* " == *"com.docker.compose.service=tenant-proxy"* ]]; then echo fixture-proxy; exit 0; fi
  if [[ "$1" == compose && " $* " == *" ps "* ]]; then echo fixture-keycloak; exit 0; fi
  if [[ "$1" == inspect ]]; then
    case "$*" in
      *KC_HTTP_RELATIVE_PATH*) printf 'KC_HOSTNAME_STRICT=false\\nKC_HTTP_RELATIVE_PATH=/auth\\n' ;;
      *KC_HOSTNAME*) : ;;
      *.Mounts*) echo openhire-test_keycloak-h2-data ;;
      *.State.Health*) echo healthy ;;
    esac
    exit 0
  fi
fi
if [[ " $* " == *" build "* && "$FAKE_FAILURE" == build ]]; then exit 1; fi
if [[ " $* " == *" --no-recreate "* && "$FAKE_FAILURE" == infra ]]; then exit 1; fi
if [[ " $* " == *" --force-recreate "* && "$FAKE_FAILURE" == startup ]]; then exit 1; fi
if [[ " $* " == *" logs --follow "* ]]; then kill -INT "$PPID"; fi
exit 0
`, { mode: 0o700 });
  try {
    const result = spawnSync('bash', [entry, ...args], {
      cwd: directory,
      encoding: 'utf8',
      env: {
        ...process.env, PATH: `${directory}:${process.env.PATH}`,
        FAKE_DOCKER_CALLS: calls, FAKE_FAILURE: '', FAKE_PORTAL: '0',
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
  assert.match(result.calls[infra], /keycloak redis kafka mailpit$/);
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

test('resume deployments have no antivirus dependency and the old flag is inert', () => {
  const normal = run(['--no-build', '--no-follow', 'resume-processor']);
  const legacy = run(['--no-build', '--no-follow', '--no-scan', 'resume-processor']);
  assert.equal(normal.status, 0, normal.stderr);
  assert.equal(legacy.status, 0, legacy.stderr);
  assert.deepEqual(legacy.calls, normal.calls);
  for (const line of normal.calls) {
    assert.doesNotMatch(line, /clamav|local-no-scan|RESUME_SCAN_ENABLED/);
  }
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

test('ordinary application deployments preserve an existing tenant portal configuration', () => {
  const result = run(['--no-build', '--no-follow', 'job-service'], { FAKE_PORTAL: '1' });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /preserving portal authentication settings/);
  const recreate = result.calls.find(line => line.includes('--force-recreate') && line.endsWith('job-service'));
  assert.match(recreate, /--file .*docker-compose\.tenant-portals\.yml/);
  assert.ok(result.calls.some(line => line.includes('--force-recreate tenant-proxy')));
  assert.ok(!result.calls.some(line => line.includes('--force-recreate keycloak')));
});

test('phase launcher also retains portal settings without requiring a new flag', () => {
  const result = run(['phase8'], { FAKE_PORTAL: '1' }, launcher);
  assert.equal(result.status, 0, result.stderr);
  const start = result.calls.find(line => line.includes('up -d --no-deps --build integration-hub'));
  assert.match(start, /--file .*docker-compose\.tenant-portals\.yml/);
});
