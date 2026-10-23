import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
test('every backend application config has no implicit demo tenant', () => {
  const files = readdirSync(root, { withFileTypes: true }).filter(e => e.isDirectory())
    .flatMap(e => ['application.yml', 'application.yaml'].map(name => join(root, e.name, 'src/main/resources', name)))
    .filter(existsSync);
  assert.ok(files.length >= 18, 'Expected all runnable backend service configurations');
  for (const file of files) {
    const text = readFileSync(file, 'utf8');
    assert.doesNotMatch(text, /demo-org|demo-tenant/, file);
    assert.match(text, /default-id: \$\{(?:DEFAULT_TENANT_ID|OPENHIRE_DEFAULT_TENANT):\}/, file);
  }
});
test('Compose does not reinstate demo tenants or default sample seeding', () => {
  const text = readFileSync(join(root, 'docker-compose.yml'), 'utf8');
  assert.doesNotMatch(text, /demo-org|demo-tenant/);
  assert.match(text, /SAMPLE_DATA_ENABLED: \$\{SAMPLE_DATA_ENABLED:-false\}/);
  assert.match(text, /OPENHIRE_ANALYTICS_METRICS_TENANTS: \$\{PROMETHEUS_TENANTS:-\}/);
});
test('Java injection defaults cannot silently restore the removed demo tenant', () => {
  for (const file of [
    'service-support/src/main/java/com/openhire/support/Access.java',
    'api-gateway/src/main/java/com/openhire/gateway/controller/BffSessionController.java',
    'application-service/src/main/java/com/openhire/applicationservice/service/ConsentTexts.java',
    'identity-service/src/main/java/com/openhire/identity/config/SampleIdentityDataSeeder.java',
    'analytics-service/src/main/java/com/openhire/analytics/controller/SloMetricsController.java',
  ]) {
    assert.doesNotMatch(readFileSync(join(root, file), 'utf8'), /@Value\("\$\{[^}]*:demo-/);
  }
  const sample = readFileSync(join(root, 'identity-service/src/main/resources/application.yml'), 'utf8');
  assert.match(sample, /enabled: \$\{SAMPLE_DATA_ENABLED:false\}/);
});
