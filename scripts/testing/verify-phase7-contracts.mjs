import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const root = new URL('../../', import.meta.url);
const contract = JSON.parse(readFileSync(new URL('contracts/phase7-api.openapi.json', root), 'utf8'));
const controllers = {
  'compliance-service/src/main/java/com/openhire/compliance/adapter/in/web/': ['StaffController', 'PublicController', 'AuditController'],
  'analytics-service/src/main/java/com/openhire/analytics/adapter/in/web/': ['AnalyticsController', 'PlatformController', 'DeadLetterController', 'SloMetricsController'],
  'ai-gateway/src/main/java/com/openhire/aigateway/adapter/in/web/': ['LogRetentionController'],
};
const actual = new Set();
for (const [folder, names] of Object.entries(controllers)) {
  for (const name of names) {
    const source = readFileSync(new URL(`${folder}${name}.java`, root), 'utf8');
    const base = source.match(/@RequestMapping\("([^"]+)"\)/)?.[1];
    assert(base, `Class mapping missing: ${name}`);
    for (const mapping of source.matchAll(/@(Get|Post|Put|Delete|Patch)Mapping(?:\((?:value\s*=\s*)?"([^"]*)"[^)]*\))?/g)) {
      const path = (base + (mapping[2] ?? '')).replace(/^\/api\/v1/, '');
      const method = mapping[1].toLowerCase();
      assert(contract.paths[path]?.[method], `Undocumented endpoint: ${method} ${path}`);
      actual.add(`${method} ${path}`);
    }
  }
}
const ids = new Set();
for (const [path, methods] of Object.entries(contract.paths)) {
  for (const [method, operation] of Object.entries(methods)) {
    assert(actual.has(`${method} ${path}`), `Contract has no controller: ${method} ${path}`);
    assert(!ids.has(operation.operationId), `Duplicate operationId: ${operation.operationId}`);
    ids.add(operation.operationId);
    for (const [, name] of path.matchAll(/\{(\w+)\}/g))
      assert(operation.parameters.some(p => p.in === 'path' && p.name === name && p.required));
  }
}
function references(value) {
  if (!value || typeof value !== 'object') return;
  if (value.$ref) {
    assert(value.$ref.startsWith('#/'));
    const target = value.$ref.slice(2).split('/').reduce((node, key) => node?.[key], contract);
    assert(target, `Unresolved reference: ${value.$ref}`);
  }
  Object.values(value).forEach(references);
}
references(contract);
console.log(`PASS: ${actual.size} Phase 7 REST operations match controllers; operation IDs, path parameters and schema references are valid.`);
