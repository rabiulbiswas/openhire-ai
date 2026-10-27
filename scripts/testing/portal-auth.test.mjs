import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('../../', import.meta.url));
const read = path => readFileSync(join(root, path), 'utf8');

test('same-host override enables constrained local issuer validation across all 18 backend services', () => {
  const config = read('docker-compose.tenant-portals.yml');
  const services = [...read('settings.gradle').matchAll(/^include '([^']+)'/gm)]
    .map(m => m[1]).filter(name => !['service-support', 'platform-starter'].includes(name));
  for (const service of services) assert.match(config, new RegExp(`^  ${service}:$`, 'm'));
  assert.match(config, /KC_HOSTNAME: !reset null/);
  assert.match(config, /KC_HOSTNAME_ADMIN: !reset null/);
  assert.match(config, /KC_HOSTNAME_STRICT: "false"/);
  assert.match(config, /KC_HTTP_RELATIVE_PATH: \/auth/);
  assert.match(config, /KC_PROXY_HEADERS: xforwarded/);
  assert.match(config, /STAFF_OIDC_ISSUER: http:\/\/admin\.localhost\/auth\/realms\/openhire-staff/);
  assert.match(config, /OPENHIRE_PORTALS_SAME_HOST_AUTH: "true"/);
  assert.match(config, /OIDC_JWK_SET_URI: http:\/\/keycloak:8080\/auth\/realms\/openhire-staff/);
  assert.match(config, /keycloak-h2-data:\/opt\/keycloak\/data\b/);
  assert.match(config, /ports: !override \["127\.0\.0\.1:/);
});

test('auth proxy overrides forwarding headers, does not log query strings, and blocks admin routes', () => {
  const config = read('infra/tenant-portals/nginx.conf');
  const auth = config.slice(config.indexOf('server_name admin.localhost;'));
  assert.match(auth, /location ~ \^\/auth\/\(realms\/openhire-staff\|resources\)/);
  assert.match(auth, /proxy_pass http:\/\/keycloak:8080/);
  for (const header of ['Host $host', 'X-Forwarded-Host $host', 'X-Forwarded-Proto http', 'X-Forwarded-Port 80'])
    assert.ok(auth.includes(`proxy_set_header ${header};`));
  assert.equal((auth.match(/auth_request \/_portal_check;/g) || []).length, 2);
  assert.equal((auth.match(/proxy_set_header X-Forwarded-Prefix "";/g) || []).length, 2);
  assert.equal((auth.match(/proxy_set_header X-Forwarded-Ssl "";/g) || []).length, 2);
  assert.equal((auth.match(/location \/auth\/ \{ return 404; \}/g) || []).length, 2);
  assert.match(read('api-gateway/src/main/java/com/openhire/gateway/controller/BffSessionController.java'), /return portals\.resolve\(exchange, null\)/);
  assert.match(config, /log_format portal_safe '\$remote_addr \$request_method \$status';/);
  assert.doesNotMatch(auth, /proxy_cookie_domain|sub_filter|proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for/);
});

function fixture(script, args = [], env = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'openhire-portal-auth-'));
  const calls = join(directory, 'calls');
  const setup = join(directory, 'scripts/setup');
  mkdirSync(setup, { recursive: true });
  writeFileSync(join(setup, script), read(`scripts/setup/${script}`));
  writeFileSync(calls, '');
  writeFileSync(join(directory, 'docker'), `#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$FAKE_CALLS"
case "$1" in
  compose) [[ "\${FAKE_EXISTING:-1}" == 0 ]] || echo fixture-keycloak ;;
  inspect)
    case "$3" in
      *KC_HTTP_RELATIVE_PATH*) printf 'KC_HOSTNAME_STRICT=false\\nKC_HTTP_RELATIVE_PATH=/auth\\n' ;;
      *KC_HOSTNAME*) echo "KC_HOSTNAME=\${FAKE_HOST-}" ;;
      *KC_DB*) echo 'KC_DB=dev-file' ;;
      *.Mounts*) echo "\${FAKE_VOLUME:-fixture_keycloak-h2-data}" ;;
      *.Image*) echo fixture-image ;;
      *.State.Running*) echo true ;;
    esac ;;
  cp)
    [[ "\${FAKE_COPY_FAIL:-0}" == 0 ]] || exit 1
    mkdir -p "$3"; printf 'synthetic fixture' > "$3/keycloakdb.mv.db" ;;
esac
`, { mode: 0o700 });
  try {
    const result = spawnSync('bash', [join(setup, script), ...args], {
      encoding: 'utf8', env: { ...process.env, PATH: `${directory}:${process.env.PATH}`,
        OPENHIRE_DOCKER_PROJECT: 'fixture', FAKE_CALLS: calls, ...env },
    });
    return { ...result, calls: readFileSync(calls, 'utf8'), backup: existsSync(join(directory, 'backups')) };
  } finally { rmSync(directory, { recursive: true, force: true }); }
}

test('read-only gate rejects legacy hostname and foreign storage without mutating Docker', () => {
  for (const env of [{ FAKE_HOST: 'http://localhost:8180' }, { FAKE_VOLUME: 'other-volume' }]) {
    const result = fixture('check-portal-keycloak.sh', [], env);
    assert.equal(result.status, 1, result.stderr);
    assert.match(result.stderr, /cutover is required/);
    assert.doesNotMatch(result.calls, /(^|\n)(stop|start|run|volume|cp) /);
  }
});

test('read-only gate permits prepared and fresh installations', () => {
  assert.equal(fixture('check-portal-keycloak.sh').status, 0);
  assert.equal(fixture('check-portal-keycloak.sh', [], { FAKE_EXISTING: '0' }).status, 0);
});

test('storage migration requires explicit apply; success preserves backup and leaves source stopped', () => {
  const help = fixture('prepare-portal-keycloak.sh');
  assert.equal(help.status, 2);
  assert.equal(help.calls, '');
  const result = fixture('prepare-portal-keycloak.sh', ['--apply'], { FAKE_VOLUME: 'legacy' });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(result.backup);
  assert.match(result.calls, /stop --time 60 fixture-keycloak/);
  assert.match(result.calls, /cp fixture-keycloak:\/opt\/keycloak\/data\/h2/);
  assert.doesNotMatch(result.calls, /(^|\n)(start|rm|restart) /);
});

test('storage migration failure restarts original source instead of recreating or deleting it', () => {
  const result = fixture('prepare-portal-keycloak.sh', ['--apply'], { FAKE_VOLUME: 'legacy', FAKE_COPY_FAIL: '1' });
  assert.notEqual(result.status, 0);
  assert.match(result.calls, /start fixture-keycloak/);
  assert.doesNotMatch(result.calls, /(^|\n)(rm|restart) /);
});
