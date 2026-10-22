import assert from 'node:assert/strict';

const accounts = [
  ['recruiter', 'RECRUITER'], ['hr-admin', 'HR_ADMIN'], ['hiring-manager', 'HIRING_MANAGER'],
  ['interviewer', 'INTERVIEWER'], ['org-admin', 'ORG_ADMIN'], ['compliance', 'COMPLIANCE_OFFICER'],
  ['super-admin', 'SUPER_ADMIN'], ['agency', 'AGENCY_RECRUITER'], ['employee', 'EMPLOYEE'],
  ['candidate', 'CANDIDATE'],
].map(([name, role]) => ({ username: `demo.${name}@openhire.local`, role,
  realm: role === 'CANDIDATE' ? 'openhire-candidate' : 'openhire-staff', name }));

if (process.argv.includes('--help')) {
  console.log('Local UI accounts: node scripts/setup/seed-ui-users.mjs --apply\nRequired: KEYCLOAK_ADMIN_PASSWORD, OPENHIRE_DEMO_PASSWORD (12+ characters).\nOptional: KEYCLOAK_ADMIN_USERNAME, OPENHIRE_KEYCLOAK_URL (loopback only).\nWithout --apply, only prints the account inventory. Existing accounts/passwords are never overwritten.');
  process.exit(0);
}
assert(process.argv.slice(2).every(arg => arg === '--apply'), 'Unknown argument');
for (const account of accounts) console.log(`${account.username} | ${account.role} | ${account.realm}`);
if (!process.argv.includes('--apply')) process.exit(0);

const base = new URL(process.env.OPENHIRE_KEYCLOAK_URL || 'http://localhost:8180');
assert(['localhost', '127.0.0.1', '[::1]'].includes(base.hostname), 'Demo seeding is restricted to loopback Keycloak');
assert(['http:', 'https:'].includes(base.protocol) && !base.username && !base.password && base.pathname === '/' && !base.search && !base.hash, 'Use a plain local Keycloak origin');
const password = process.env.OPENHIRE_DEMO_PASSWORD;
assert(password && password.length >= 12, 'Set OPENHIRE_DEMO_PASSWORD to at least 12 characters');
assert(/[A-Z]/.test(password) && /[a-z]/.test(password) && /[0-9]/.test(password) && /[^A-Za-z0-9]/.test(password), 'Demo password must include uppercase, lowercase, a digit and a special character');
assert(process.env.KEYCLOAK_ADMIN_PASSWORD, 'Set KEYCLOAK_ADMIN_PASSWORD');
const root = base.origin;
const login = await fetch(`${root}/realms/master/protocol/openid-connect/token`, {
  method: 'POST', redirect: 'error', signal: AbortSignal.timeout(15000),
  body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli',
    username: process.env.KEYCLOAK_ADMIN_USERNAME || 'admin', password: process.env.KEYCLOAK_ADMIN_PASSWORD }),
});
assert(login.ok, `Administration login failed (${login.status})`);
const token = (await login.json()).access_token;
assert(token, 'No administration token received');
async function api(realm, path, method = 'GET', body) {
  const result = await fetch(`${root}/admin/realms/${realm}${path}`, {
    method, redirect: 'error', signal: AbortSignal.timeout(15000),
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  assert(result.ok, `${realm}: ${method} ${path.split('?')[0]} failed (${result.status}); no credentials are logged`);
  return result.status === 204 ? null : result.text().then(text => text ? JSON.parse(text) : null);
}

// Preflight both realms and all roles before creating users. No roles or realm policies are changed.
const roles = new Map();
for (const account of accounts) roles.set(account.role, await api(account.realm, `/roles/${account.role}`));
for (const account of accounts) {
  const query = `/users?exact=true&username=${encodeURIComponent(account.username)}`;
  let users = await api(account.realm, query);
  assert(users.length <= 1, `Ambiguous account: ${account.username}`);
  if (users.length) {
    const existing = users[0];
    const mapped = await api(account.realm, `/users/${existing.id}/role-mappings/realm`);
    assert(existing.enabled && mapped.some(role => role.name === account.role),
      `Existing ${account.username} is disabled or lacks ${account.role}; review it in Keycloak. It was not modified.`);
    console.log(`Preserved ${account.username} (existing password unchanged)`);
    continue;
  }
  await api(account.realm, '/users', 'POST', {
    username: account.username, email: account.username, firstName: 'Demo', lastName: account.name,
    enabled: true, emailVerified: true, requiredActions: [],
    attributes: { organization_id: ['demo-org'], organization_name: ['Local workspace'] },
    credentials: [{ type: 'password', value: password, temporary: false }],
  });
  users = await api(account.realm, query);
  assert(users.length === 1, `Unable to locate created account ${account.username}`);
  await api(account.realm, `/users/${users[0].id}/role-mappings/realm`, 'POST', [roles.get(account.role)]);
  const mapped = await api(account.realm, `/users/${users[0].id}/role-mappings/realm`);
  assert(mapped.some(role => role.name === account.role), `Role verification failed for ${account.username}`);
  console.log(`Created ${account.username} with ${account.role}`);
}
console.log('Local Keycloak accounts ready. New accounts use OPENHIRE_DEMO_PASSWORD. No realm policy, existing password, backend business record or client redirect was changed.');
