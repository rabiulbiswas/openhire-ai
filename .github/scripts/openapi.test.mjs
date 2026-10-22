import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { inventory } from './affected.mjs';

const read = path => fs.readFileSync(path, 'utf8');
const gateway = 'api-gateway/src/main/java/com/openhire/gateway/';

test('OpenAPI dropdown covers exactly the 18 executable modules with fixed targets', () => {
  const source = read(gateway + 'config/OpenApiPortalConfiguration.java');
  const services = [...source.matchAll(/new ServiceSpec\("([^"]+)"/g)].map(m => m[1]);
  assert.deepEqual([...services, 'api-gateway'].sort(), inventory().services.sort());
  assert.match(source, /\.method\(HttpMethod.GET\)/);
  assert.match(source, /\.tokenRelay\(\)/);
  assert.match(source, /Map.entry\("supportedSubmitMethods", List.of\(\)\)/);
  assert.match(source, /Map.entry\("queryConfigEnabled", false\)/);
  assert.match(source, /Map.entry\("validatorUrl", ""\)/);
});

test('every specification is opt-in and locks the Springdoc starter for its web stack', () => {
  for (const service of inventory().services) {
    const config = read(`${service}/src/main/resources/application.yml`);
    assert.match(config, /enabled: \$\{OPENHIRE_API_DOCS_ENABLED:false\}/, service);
    assert.match(config, /paths-to-exclude: .*\/api\/v1\/internal\/\*\*/, service);
    const lock = read(`${service}/gradle.lockfile`);
    const starter = service === 'api-gateway' ? 'webflux-ui' : 'webmvc-api';
    assert.ok(lock.includes(`org.springdoc:springdoc-openapi-starter-${starter}:3.1.1=`), service);
    if (service !== 'api-gateway') assert.ok(!lock.includes('springdoc-openapi-starter-webmvc-ui'), service);
  }
  const compose = read('docker-compose.yml');
  assert.equal((compose.match(/OPENHIRE_API_DOCS_ENABLED: \$\{OPENHIRE_API_DOCS_ENABLED:-false\}/g) || []).length, 18);
});

test('gateway portal requires staff authentication and stays hidden in local security mode', () => {
  const guard = read(gateway + 'security/DocumentationAccess.java');
  assert.match(guard, /!enabled \|\| !"oidc"\.equals\(mode\)/);
  assert.match(guard, /HttpStatus.NOT_FOUND/);
  assert.match(guard, /"openhire-staff"\.equals\(oauth.getAuthorizedClientRegistrationId\(\)\)/);
  const security = read(gateway + 'security/GatewaySecurityConfiguration.java');
  assert.match(security, /pathMatchers\(DocumentationAccess.PATHS\)/);
  assert.match(security, /map\(DocumentationAccess::staffSession\)/);
  assert.match(security, /DocumentationAccess.afterLogin/);
});
