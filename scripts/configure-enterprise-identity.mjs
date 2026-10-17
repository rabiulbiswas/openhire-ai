#!/usr/bin/env node
// Operator-run migration. No calls or changes without --apply; never prints client secrets.
import { createHash } from 'node:crypto';
const apply = process.argv.includes('--apply');
const tenant = process.env.SCIM_TENANT_ID;
if (!tenant || !/^[A-Za-z0-9_-]{1,100}$/.test(tenant)) throw new Error('Set SCIM_TENANT_ID to an existing organization ID');
const base = new URL(process.env.KEYCLOAK_BASE_URL || 'http://localhost:8180');
if (base.username || base.password || base.search || base.hash || (base.protocol !== 'https:' && !['localhost', '127.0.0.1'].includes(base.hostname))) throw new Error('Use HTTPS for non-local Keycloak');
const realm = process.env.KEYCLOAK_REALM || 'openhire-staff';
const clientId = `openhire-scim-${createHash('sha256').update(tenant).digest('hex').slice(0, 24)}`;
if (!apply) {
  console.log(JSON.stringify({ status: 'DRY_RUN', tenant, clientId, changes: ['Create or validate deny-first-login SAML flow', 'Protect SCIM ownership attribute', 'Register tenant-specific SCIM service client and scope'], note: 'Use --apply with KEYCLOAK_ADMIN_TOKEN. Existing incompatible clients or flows are rejected.' }));
} else {
  const token = process.env.KEYCLOAK_ADMIN_TOKEN;
  if (!token) throw new Error('KEYCLOAK_ADMIN_TOKEN required; retrieve it securely, never commit it');
  const root = `${base.toString().replace(/\/$/, '')}/admin/realms/${encodeURIComponent(realm)}`;
  async function request(path, method = 'GET', body) {
    const response = await fetch(root + path, { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), redirect: 'error', signal: AbortSignal.timeout(10000) });
    if (!response.ok) throw new Error(`Enterprise migration ${method} failed with HTTP ${response.status}; no credentials or response body logged`);
    const text = await response.text(); return text ? JSON.parse(text) : undefined;
  }
  const flowName = 'openhire-prelinked-only';
  const flows = await request('/authentication/flows');
  if (!flows.some(flow => flow.alias === flowName)) {
    await request('/authentication/flows', 'POST', { alias: flowName, description: 'OpenHire: deny first broker login; administrator pre-linking required', providerId: 'basic-flow', topLevel: true, builtIn: false });
    await request(`/authentication/flows/${flowName}/executions/execution`, 'POST', { provider: 'deny-access-authenticator' });
    const [execution] = await request(`/authentication/flows/${flowName}/executions`);
    await request(`/authentication/flows/${flowName}/executions`, 'PUT', { ...execution, requirement: 'REQUIRED' });
  }
  const executions = await request(`/authentication/flows/${flowName}/executions`);
  if (executions.length !== 1 || executions[0].providerId !== 'deny-access-authenticator' || executions[0].requirement !== 'REQUIRED') throw new Error('Existing SAML flow is incompatible; refusing to weaken or overwrite it');
  const profile = await request('/users/profile');
  profile.attributes ||= [];
  const existing = profile.attributes.find(a => a.name === 'openhire_scim_id');
  const attribute = { ...existing, name: 'openhire_scim_id', multivalued: false, permissions: { view: ['admin'], edit: ['admin'] }, validations: { length: { max: 100 } } };
  if (existing) Object.assign(existing, attribute); else profile.attributes.push(attribute);
  await request('/users/profile', 'PUT', profile);
  let scope = (await request('/client-scopes')).find(s => s.name === 'scim.provision');
  if (!scope) {
    await request('/client-scopes', 'POST', { name: 'scim.provision', protocol: 'openid-connect', attributes: { 'include.in.token.scope': 'true' } });
    scope = (await request('/client-scopes')).find(s => s.name === 'scim.provision');
  }
  const expected = [
    { name: 'scim-tenant', protocol: 'openid-connect', protocolMapper: 'oidc-hardcoded-claim-mapper', config: { 'claim.name': 'organization_id', 'claim.value': tenant, 'jsonType.label': 'String', 'access.token.claim': 'true' } },
    { name: 'scim-audience', protocol: 'openid-connect', protocolMapper: 'oidc-audience-mapper', config: { 'included.custom.audience': 'openhire-scim', 'access.token.claim': 'true' } },
  ];
  let [client] = await request(`/clients?clientId=${encodeURIComponent(clientId)}`);
  if (!client) {
    await request('/clients', 'POST', { clientId, name: `SCIM provisioning for ${tenant}`, enabled: true, protocol: 'openid-connect', publicClient: false,
      serviceAccountsEnabled: true, standardFlowEnabled: false, directAccessGrantsEnabled: false, fullScopeAllowed: false, protocolMappers: expected });
    [client] = await request(`/clients?clientId=${encodeURIComponent(clientId)}`);
  }
  const detail = await request(`/clients/${client.id}`);
  if (detail.publicClient || detail.standardFlowEnabled || detail.directAccessGrantsEnabled || !detail.serviceAccountsEnabled || detail.fullScopeAllowed) throw new Error('Existing SCIM client has incompatible security settings');
  for (const mapping of expected) {
    const actual = detail.protocolMappers?.find(m => m.name === mapping.name);
    if (!actual || actual.protocolMapper !== mapping.protocolMapper || Object.entries(mapping.config).some(([k, v]) => actual.config[k] !== v)) throw new Error('Existing SCIM client has incompatible tenant/audience mappings');
  }
  await request(`/clients/${client.id}/default-client-scopes/${scope.id}`, 'PUT');
  console.log(JSON.stringify({ status: 'CONFIGURED', tenant, clientId, note: 'Retrieve the generated client secret in Keycloak and store it securely. Enable SCIM for this tenant in OpenHire. No SAML provider or user was linked automatically.' }));
}
