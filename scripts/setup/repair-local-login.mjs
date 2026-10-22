import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

if (process.argv.includes('--help')) {
  console.log('Usage: KEYCLOAK_ADMIN_PASSWORD=... node scripts/setup/repair-local-login.mjs --apply\nRepairs local callback allowlists, basic scope and organization/role claims. Preserves users, passwords and unrelated client settings.');
  process.exit(0);
}
assert(process.argv.length === 3 && process.argv[2] === '--apply', 'Use --help or explicitly pass --apply');
const url = new URL(process.env.OPENHIRE_KEYCLOAK_URL || 'http://localhost:8180');
assert(['localhost', '127.0.0.1', '[::1]'].includes(url.hostname) && ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password && url.pathname === '/' && !url.search && !url.hash, 'Only a loopback Keycloak origin is allowed');
assert(process.env.KEYCLOAK_ADMIN_PASSWORD, 'Set KEYCLOAK_ADMIN_PASSWORD');
const login = await fetch(`${url.origin}/realms/master/protocol/openid-connect/token`, {
  method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
  body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: process.env.KEYCLOAK_ADMIN_USERNAME || 'admin', password: process.env.KEYCLOAK_ADMIN_PASSWORD }),
});
assert(login.ok, `Admin authentication failed (${login.status})`);
const { access_token: token } = await login.json();
assert(token, 'Missing administration token');
async function api(realm, route, method = 'GET', body) {
  const response = await fetch(`${url.origin}/admin/realms/${realm}${route}`, { method, redirect: 'error', signal: AbortSignal.timeout(15000), headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) });
  assert(response.ok, `${realm}: ${method} ${route.split('?')[0]} failed (${response.status})`);
  const text = await response.text(); return text ? JSON.parse(text) : null;
}
const staff = JSON.parse(readFileSync(new URL('../../infra/keycloak/openhire-staff-realm.json', import.meta.url))).clients.find(c => c.clientId === 'openhire-web');
for (const [realm, clientId] of [['openhire-staff', 'openhire-web'], ['openhire-candidate', 'openhire-candidate-web']]) {
  const desired = JSON.parse(readFileSync(new URL(`../../infra/keycloak/${realm}-realm.json`, import.meta.url))).clients.find(c => c.clientId === clientId);
  const clients = await api(realm, `/clients?clientId=${clientId}`);
  assert(clients.length === 1, `Expected one ${clientId} client`);
  const client = clients[0];
  const scopes = await api(realm, '/client-scopes');
  const basic = scopes.find(scope => scope.name === 'basic'); assert(basic, `${realm}: basic scope missing`);
  const profile = await api(realm, '/users/profile');
  profile.attributes ||= [];
  for (const name of ['organization_id', 'organization_name']) {
    const existing = profile.attributes.find(a => a.name === name);
    const managed = { ...existing, name, displayName: name, multivalued: false, permissions: { view: ['admin', 'user'], edit: ['admin'] }, validations: { ...existing?.validations, length: { max: 200 } } };
    if (existing) Object.assign(existing, managed); else profile.attributes.push(managed);
  }
  await api(realm, '/users/profile', 'PUT', profile);
  client.redirectUris = [...new Set([...(client.redirectUris || []), ...desired.redirectUris])];
  client.webOrigins = [...new Set([...(client.webOrigins || []), ...desired.webOrigins])];
  await api(realm, `/clients/${client.id}`, 'PUT', client);
  await api(realm, `/clients/${client.id}/default-client-scopes/${basic.id}`, 'PUT');
  const mappers = await api(realm, `/clients/${client.id}/protocol-mappers/models`);
  for (const desiredMapper of staff.protocolMappers) {
    const existing = mappers.find(m => m.name === desiredMapper.name);
    await api(realm, `/clients/${client.id}/protocol-mappers/models${existing ? `/${existing.id}` : ''}`, existing ? 'PUT' : 'POST', { ...existing, ...desiredMapper });
  }
  console.log(`Repaired ${realm}: exact localhost callbacks and session claims; users and credentials preserved.`);
}
