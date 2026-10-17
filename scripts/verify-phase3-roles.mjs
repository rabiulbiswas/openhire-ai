// Local-only role acceptance. Creates uniquely named Keycloak fixture users; removes only those users.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { staffToken } from './phase3-staff-token.mjs';
import { resumePdf } from './phase3-pdf-fixture.mjs';
const gateway = process.env.OPENHIRE_GATEWAY_URL || 'http://localhost:8080';
const keycloak = process.env.OPENHIRE_KEYCLOAK_URL || 'http://localhost:8180';
assert(['localhost', '127.0.0.1'].includes(new URL(keycloak).hostname), 'Fixture provisioning is local-only');
const jobId = process.env.OPENHIRE_JOB_ID;
assert(jobId, 'Set OPENHIRE_JOB_ID');
const run = randomUUID();
const seed = JSON.parse(readFileSync(new URL('../infra/keycloak/openhire-staff-realm.json', import.meta.url)));
async function login(username, password) {
  process.env.OPENHIRE_TEST_USERNAME = username;
  process.env.OPENHIRE_TEST_PASSWORD = password;
  const token = await staffToken();
  const headers = { Authorization: `Bearer ${token}` };
  const res = await fetch(`${gateway}/bff/csrf`, { headers });
  assert.equal(res.status, 200);
  headers.Cookie = res.headers.getSetCookie().map(v => v.split(';')[0]).join('; ');
  headers['X-XSRF-TOKEN'] = decodeURIComponent(res.headers.getSetCookie().find(v => v.startsWith('XSRF-TOKEN=')).split(';')[0].slice(11));
  return { headers, claims: JSON.parse(Buffer.from(token.split('.')[1], 'base64url')) };
}
async function seeded(username) { const u = seed.users.find(u => u.username === username); return login(username, u.credentials[0].value); }
async function api(path, headers, body, method = 'GET', expected = 200) {
  const res = await fetch(`${gateway}/api/v1${path}`, { method, headers: { ...headers, ...(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}) }, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(45000) });
  const text = await res.text();
  assert.equal(res.status, expected, `${method} ${path}: ${res.status} ${text.slice(0, 200)}`);
  return text ? JSON.parse(text) : null;
}
const recruiter = await seeded('recruiter@openhire.local');
const admin = await seeded('admin@openhire.local');
const foreign = await seeded('admin@second.openhire.local');
const policy = await api('/public/consent', {});
const adminResponse = await fetch(`${keycloak}/realms/master/protocol/openid-connect/token`, { method: 'POST', body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: process.env.KEYCLOAK_ADMIN_USERNAME || 'admin', password: process.env.KEYCLOAK_ADMIN_PASSWORD || 'admin' }) });
assert.equal(adminResponse.status, 200, 'Local fixture provisioning requires Keycloak administration');
const adminToken = (await adminResponse.json()).access_token;
const adminHeaders = { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' };
const users = [], keys = [];
let assignment;
async function provision(role) {
  const username = `phase3-${role.toLowerCase()}-${run}@example.test`, password = randomUUID() + 'aA1!';
  const res = await fetch(`${keycloak}/admin/realms/openhire-staff/users`, { method: 'POST', headers: adminHeaders, body: JSON.stringify({ username, email: username, firstName: 'Phase3', lastName: 'Fixture', enabled: true, emailVerified: true, attributes: { organization_id: ['demo-org'] }, credentials: [{ type: 'password', value: password, temporary: false }] }) });
  assert.equal(res.status, 201);
  const id = res.headers.get('location').split('/').pop(); users.push(id);
  const roleResponse = await fetch(`${keycloak}/admin/realms/openhire-staff/roles/${role}`, { headers: adminHeaders });
  assert.equal(roleResponse.status, 200);
  const mapping = await fetch(`${keycloak}/admin/realms/openhire-staff/users/${id}/role-mappings/realm`, { method: 'POST', headers: adminHeaders, body: JSON.stringify([await roleResponse.json()]) });
  assert.equal(mapping.status, 204);
  const session = await login(username, password);
  assert.equal(session.claims.organization_id, 'demo-org', `Fixture ${role} is missing its tenant claim`);
  assert(session.claims.sub, `Fixture ${role} is missing its subject claim`);
  assert(session.claims.realm_access?.roles?.includes(role), `Fixture token is missing ${role}`);
  return session;
}
function submission(source, email = `candidate-${run}@example.test`) {
  const body = new FormData();
  for (const [k,v] of Object.entries({ jobId, source, name: `Channel fixture ${run}`, email, processingConsent: 'true', consentVersion: policy.version })) body.set(k,v);
  body.set('resume', new Blob([resumePdf()], { type: 'application/pdf' }), 'fixture.pdf');
  return body;
}
try {
  const agency = await provision('AGENCY_RECRUITER'), employee = await provision('EMPLOYEE');
  await api('/applications', { ...agency.headers, 'Idempotency-Key': randomUUID() }, submission('AGENCY'), 'POST', 403);
  assignment = `/partner-assignments/${agency.claims.sub}/${jobId}`;
  await api(assignment, recruiter.headers, { active: true }, 'PUT');
  const assigned = await api('/partner/jobs', agency.headers);
  assert(assigned.data.some(j => j.id === jobId), `Assigned job missing for fixture actor ${agency.claims.sub}: ${JSON.stringify(assigned)}`);
  const application = await api('/applications', { ...agency.headers, 'Idempotency-Key': randomUUID() }, submission('AGENCY'), 'POST');
  const career = submission('CAREER'); career.set('tenantId', 'demo-org'); career.set('receiptToken', randomUUID() + randomUUID());
  const samePerson = await api('/public/applications', { 'Idempotency-Key': randomUUID() }, career, 'POST');
  let rows;
  for (let i=0;i<60;i++) {
    rows = (await api(`/jobs/${jobId}/applications`, recruiter.headers)).data;
    const a=rows.find(r=>r.applicationId===application.id), b=rows.find(r=>r.applicationId===samePerson.id);
    if (a?.candidateId && b?.candidateId) { assert.equal(a.candidateId,b.candidateId); break; }
    await new Promise(r=>setTimeout(r,1000));
  }
  const card=rows.find(r=>r.applicationId===application.id); assert(card?.candidateId);
  const view=await api(`/candidates/${card.candidateId}/360`, recruiter.headers);
  assert(view.provenance && view.consents.processing);
  await api(`/candidates/${card.candidateId}/360`, foreign.headers, undefined, 'GET', 404);
  await api(`/applications/${application.id}/shortlist`, agency.headers, undefined, 'POST', 403);
  const visible=(await api('/partner/submissions',agency.headers)).data.find(r=>r.id===application.id);
  assert(visible && !('score' in visible) && !('assessment' in visible));
  await api('/applications', { ...employee.headers,'Idempotency-Key':randomUUID() }, submission('INTERNAL','someone-else@example.test'),'POST',403);
  await api('/applications', { ...employee.headers,'Idempotency-Key':randomUUID() }, submission('INTERNAL',employee.claims.email),'POST');
  await api('/applications', { ...employee.headers,'Idempotency-Key':randomUUID() }, submission('REFERRAL',`referral-${run}@example.test`),'POST');
  assert.equal((await api('/me/referrals',employee.headers)).data.length,2);
  console.log('PASS: agency assignment/redaction, career/agency identity convergence, employee/referral ownership, tenant isolation and provenance');
  for (const scopes of [['jobs:read'],['applications:write']]) {
    const issued=await api('/api-keys',admin.headers,{name:`Phase3 fixture ${run}`,scopes},'POST',201); keys.push(issued.apiKey.id);
    const input={jobId,name:'API Fixture',email:`api-${run}@example.test`,processingConsent:true,consentVersion:policy.version};
    const headers={'X-API-Key':issued.secret,'Idempotency-Key':randomUUID()};
    await api('/public/api-applications',headers,input,'POST',scopes.includes('applications:write')?200:403);
    await api(`/api-keys/${issued.apiKey.id}`,admin.headers,undefined,'DELETE',204);
    await api('/public/api-applications',headers,input,'POST',401);
  }
  console.log('PASS: machine intake scope enforcement and immediate key revocation');
} finally {
  if (assignment) await api(assignment,recruiter.headers,{active:false},'PUT');
  for (const id of keys) { const res=await fetch(`${gateway}/api/v1/api-keys/${id}`,{method:'DELETE',headers:admin.headers}); if(![204,404].includes(res.status)) console.error(`Fixture key cleanup requires review: ${id}`); }
  for (const id of users) { const res=await fetch(`${keycloak}/admin/realms/openhire-staff/users/${id}`,{method:'DELETE',headers:adminHeaders}); assert.equal(res.status,204,`Fixture user cleanup failed: ${id}`); }
  console.log('Removed only this run’s temporary identity users; disabled fixture assignment and revoked fixture keys. Application audit records retained.');
}
