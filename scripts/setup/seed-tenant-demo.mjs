// Local-only, additive demo fixtures. Existing users/passwords and business records are never replaced.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { request } from 'node:http';
import { resumePdf } from '../testing/phase3-pdf-fixture.mjs';

const slug = process.argv.find(a => a.startsWith('--tenant='))?.slice(9);
assert(slug && /^[a-z][a-z0-9-]{1,50}$/.test(slug), 'Supply --tenant=codelogicx');
assert(process.argv.slice(2).every(a => ['--apply', '--data-only', '--resume', '--approval-refresh', '--agency-refresh', '--self-service-refresh', '--retire-compliance'].includes(a) || a.startsWith('--tenant=')), 'Unknown argument');
const apply = process.argv.includes('--apply');
const dataOnly = process.argv.includes('--data-only');
const resume = process.argv.includes('--resume');
const approvalRefresh = process.argv.includes('--approval-refresh');
const agencyRefresh = process.argv.includes('--agency-refresh');
const selfServiceRefresh = process.argv.includes('--self-service-refresh');
const retireCompliance = process.argv.includes('--retire-compliance');
assert(!retireCompliance || (dataOnly && resume && !approvalRefresh && !agencyRefresh && !selfServiceRefresh), '--retire-compliance requires --data-only --resume and no other refresh mode');
assert(!selfServiceRefresh || (dataOnly && resume && !approvalRefresh && !agencyRefresh), '--self-service-refresh requires --data-only --resume and no other refresh mode');
assert(!agencyRefresh || (dataOnly && resume && !approvalRefresh), '--agency-refresh requires --data-only --resume and cannot combine with --approval-refresh');
assert(!approvalRefresh || (dataOnly && resume), '--approval-refresh requires --data-only --resume; existing demo accounts are preserved');
const tag = `${slug}-demo-v1`;
const cfg = '/tmp/openhire-codelogicx-seed-admin.config';
const kcContainer = 'openhire-keycloak-1';
const mongoImage = 'mongo:7.0.41-jammy';
const roles = ['ORG_ADMIN', 'RECRUITER', 'HR_ADMIN', 'HIRING_MANAGER', 'INTERVIEWER', 'EMPLOYEE', 'AGENCY_RECRUITER'];
function mongo(script) {
  let output;
  try {
    output = execFileSync('docker', ['run', '--rm', '--network', 'openhire_default', mongoImage, 'mongosh', 'mongodb://host.docker.internal:27017/openhire_identity', '--quiet', '--eval', script], { encoding: 'utf8', timeout: 45000 });
  } catch (error) {
    // Every helper prints one final JSON response, after all work. Docker Desktop
    // can finish mongosh but stall during --rm cleanup; retain its acknowledged result.
    if (error.code === 'ETIMEDOUT' && error.stdout?.trim()) output = error.stdout;
    else throw new Error('Local Mongo helper failed. Resume the same seed after checking Docker; no credentials or query contents were logged.');
  }
  try { return JSON.parse(output.trim()); }
  catch { throw new Error('Local Mongo helper did not return a completed JSON response; resume safely after checking Docker.'); }
}
const tenant = mongo(`print(JSON.stringify(db.organizations.findOne({slug:${JSON.stringify(slug)}},{name:1,slug:1,status:1})))`);
assert(tenant?.status === 'ACTIVE', 'Target tenant must exist and be ACTIVE');
const tenantId = String(tenant._id);
const existingMembers = mongo(`print(JSON.stringify(db.users.find({organizationId:${JSON.stringify(tenantId)}},{_id:1,email:1,displayName:1,roles:1,status:1}).toArray()))`);
console.log(JSON.stringify({ tenant: slug, tenantId, preservedExistingUsers: existingMembers.map(u => ({ username: u.email, roles: u.roles, status: u.status })), plannedDemoUsers: roles.map(role => ({ username: `demo.${role.toLowerCase().replaceAll('_', '-')}@${slug}.example.test`, role })), modules: ['jobs', 'approvals', 'candidates', 'applications', 'interviews', 'offers', 'messages', 'notifications', 'AI/analytics projections'], exclusions: ['No real email delivery', 'No privacy erasure or legal-hold operations', 'No fake audit ledger or fake model results', 'No SUPER_ADMIN grants', 'No existing password resets'] }));
if (!apply) process.exit(0);
assert(execFileSync('docker', ['exec', 'openhire-notification-dispatcher-1', 'printenv', 'MAIL_HOST'], { encoding: 'utf8' }).trim() === 'mailpit', 'Refusing to seed with a non-Mailpit SMTP host');
const prefix = `[DEMO:${slug.toUpperCase()}]`;
const password = process.env.OPENHIRE_SEED_PASSWORD || `OhDemo!${randomBytes(16).toString('base64url')}9aA`;
assert(password.length >= 16 && /[A-Z]/.test(password) && /[a-z]/.test(password) && /\d/.test(password) && /[^\w]/.test(password), 'Demo password must be strong and 16+ characters');
function kc(args, body) {
  return execFileSync('docker', ['exec', ...(body ? ['-i'] : []), kcContainer, '/opt/keycloak/bin/kcadm.sh', ...args, '--config', cfg], { encoding: 'utf8', input: body && JSON.stringify(body), timeout: 30000 }).trim();
}
const people = {};
if (dataOnly) {
  assert(process.env.OPENHIRE_SEED_PASSWORD, '--data-only requires the original demo password');
  for (const role of roles) {
    const username = `demo.${role.toLowerCase().replaceAll('_', '-')}@${slug}.example.test`;
    const member = existingMembers.find(u => u.email === username && u.status === 'ACTIVE' && u.roles.includes(role));
    assert(member, `Demo ${role} member missing`);
    people[role] = { id: member._id, username, password };
  }
} else {
execFileSync('docker', ['exec', kcContainer, 'sh', '-c', '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/openhire-codelogicx-seed-admin.config --server http://localhost:8080/auth --realm master --user "${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null'], { stdio: ['ignore', 'ignore', 'pipe'], timeout: 30000 });
// Validate every role before making the first account change.
for (const role of roles) kc(['get', `roles/${role}`, '-r', 'openhire-staff']);
for (const role of roles) {
  const username = `demo.${role.toLowerCase().replaceAll('_', '-')}@${slug}.example.test`;
  let users = JSON.parse(kc(['get', 'users', '-r', 'openhire-staff', '-q', `username=${username}`, '-q', 'exact=true']));
  assert(users.length <= 1, `Ambiguous username ${username}`);
  const created = users.length === 0;
  if (created) {
    const id = kc(['create', 'users', '-r', 'openhire-staff', '-f', '-', '-i'], { username, email: username, firstName: 'Demo', lastName: role.toLowerCase().replaceAll('_', ' '), enabled: true, emailVerified: true, requiredActions: [], attributes: { organization_id: [tenantId], tenant_id: [tenantId], openhire_seed: [tag] }, credentials: [{ type: 'password', value: password, temporary: false }] });
    assert(/^[0-9a-f-]{36}$/i.test(id), 'Unexpected created user ID');
    kc(['add-roles', '-r', 'openhire-staff', '--uid', id, '--rolename', role]);
    users = JSON.parse(kc(['get', 'users', '-r', 'openhire-staff', '-q', `username=${username}`, '-q', 'exact=true']));
    // Only newly created test credentials are printed, never operator/client secrets.
    console.log(JSON.stringify({ newDemoCredential: true, username, role, password }));
  }
  const user = users[0];
  assert(user.enabled && user.attributes?.organization_id?.[0] === tenantId, `Existing ${username} is disabled or belongs to another tenant; not changed`);
  const mapped = JSON.parse(kc(['get', `users/${user.id}/role-mappings/realm`, '-r', 'openhire-staff']));
  assert(mapped.some(r => r.name === role) && !mapped.some(r => r.name === 'SUPER_ADMIN'), 'Unexpected role mapping; existing permissions not changed');
  const member = { _id: user.id, organizationId: tenantId, email: username, displayName: `${tenant.name} Demo ${role.toLowerCase().replaceAll('_', ' ')}`, status: 'ACTIVE', roles: [role], createdAt: new Date().toISOString(), _class: 'com.openhire.identity.domain.IdentityModels$Member', seedTag: tag };
  mongo(`const m=${JSON.stringify(member)};m.createdAt=new Date(m.createdAt); const old=db.users.findOne({_id:m._id});if(old&&old.organizationId!==m.organizationId)throw Error("Conflicting tenant membership");db.users.updateOne({_id:m._id,organizationId:m.organizationId},{$setOnInsert:m},{upsert:true});print(JSON.stringify({ok:true}))`);
  people[role] = { id: user.id, username, password: created || process.env.OPENHIRE_SEED_PASSWORD ? password : null };
}
}
console.log('PASS: dedicated demo role accounts ready; existing accounts and passwords preserved');

const origin = `http://${slug}.localhost`;
const clientSecret = execFileSync('docker', ['exec', 'openhire-api-gateway-1', 'printenv', 'TENANT_OIDC_CLIENT_SECRET'], { encoding: 'utf8' }).trim();
assert(clientSecret, 'Tenant login client secret unavailable');
async function portal(url, options = {}) {
  const target = new URL(url, origin);
  assert(target.origin === origin, 'Unexpected portal origin');
  // Keep the verified portal Host while pinning transport to local loopback (Node DNS need not resolve *.localhost).
  const prepared = new Request(target, options);
  const bytes = options.body === undefined ? null : Buffer.from(await prepared.arrayBuffer());
  return new Promise((resolve, reject) => {
    const req = request({ hostname: '127.0.0.1', port: target.pathname.startsWith('/auth/') ? 8180 : 8080, path: target.pathname + target.search, method: options.method || 'GET', headers: { ...Object.fromEntries(prepared.headers), Host: `${slug}.localhost`, 'X-Forwarded-Host': `${slug}.localhost`, 'X-Forwarded-Proto': 'http', 'X-Forwarded-Port': '80', ...(bytes ? { 'Content-Length': bytes.length } : {}) }, timeout: 45000 }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('end', () => {
        const headers = new Headers();
        for (let i = 0; i < res.rawHeaders.length; i += 2) headers.append(res.rawHeaders[i], res.rawHeaders[i + 1]);
        resolve(new Response([204, 304].includes(res.statusCode) ? null : Buffer.concat(chunks), { status: res.statusCode, headers }));
      });
    });
    req.on('error', reject); req.on('timeout', () => req.destroy(new Error('Local portal request timed out')));
    req.end(bytes);
  });
}
const authRoles = new WeakMap();
async function login(role) {
  const person = people[role];
  assert(person.password, `Existing demo ${role} password unchanged; supply its original OPENHIRE_SEED_PASSWORD to resume`);
  const verifier = randomBytes(48).toString('base64url'), state = randomBytes(24).toString('hex');
  const redirect = `${origin}/login/oauth2/code/openhire-tenant`;
  const params = new URLSearchParams({ client_id: 'openhire-tenant', redirect_uri: redirect, response_type: 'code', scope: 'openid profile email', state, code_challenge: createHash('sha256').update(verifier).digest('base64url'), code_challenge_method: 'S256' });
  const page = await portal(`/auth/realms/openhire-staff/protocol/openid-connect/auth?${params}`);
  const html = await page.text();
  const action = html.match(/<form[^>]+action="([^"]+)"/i)?.[1]?.replaceAll('&amp;', '&');
  assert(action, `No login form for ${role}; no policies were changed`);
  const signed = await portal(action, { method: 'POST', headers: { Cookie: page.headers.getSetCookie().map(c => c.split(';')[0]).join('; ') }, body: new URLSearchParams({ username: person.username, password: person.password, credentialId: '' }) });
  const location = new URL(signed.headers.get('location') || origin);
  assert(signed.status === 302 && location.origin === origin && location.searchParams.get('state') === state && location.searchParams.get('code'), `Demo ${role} requires an additional login step; no authentication bypass attempted`);
  const exchange = await fetch('http://localhost:8180/auth/realms/openhire-staff/protocol/openid-connect/token', { method: 'POST', headers: { 'X-Forwarded-Host': `${slug}.localhost`, 'X-Forwarded-Proto': 'http', 'X-Forwarded-Port': '80' }, body: new URLSearchParams({ grant_type: 'authorization_code', client_id: 'openhire-tenant', client_secret: clientSecret, code: location.searchParams.get('code'), redirect_uri: redirect, code_verifier: verifier }), signal: AbortSignal.timeout(15000) });
  assert(exchange.ok, `Code exchange failed (${exchange.status}); credentials not logged`);
  const token = (await exchange.json()).access_token;
  const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url'));
  assert((claims.tenant_id || claims.organization_id) === tenantId && claims.sub === person.id && claims.iss === `${origin}/auth/realms/openhire-staff`, 'Unexpected demo token identity');
  const headers = { Authorization: `Bearer ${token}` };
  const csrf = await portal('/bff/csrf', { headers });
  assert(csrf.ok, `CSRF bootstrap failed for ${role} (${csrf.status}); normal security checks remain enabled`);
  const cookies = csrf.headers.getSetCookie();
  headers.Cookie = cookies.map(c => c.split(';')[0]).join('; ');
  const xsrf = cookies.find(c => c.startsWith('XSRF-TOKEN='));
  assert(xsrf, 'CSRF cookie missing');
  headers['X-XSRF-TOKEN'] = decodeURIComponent(xsrf.split(';')[0].slice('XSRF-TOKEN='.length));
  authRoles.set(headers, role);
  return headers;
}
let asRecruiter = await login('RECRUITER');
const asManager = await login('HIRING_MANAGER');
const asHr = await login('HR_ADMIN');
const asInterviewer = await login('INTERVIEWER');
async function api(path, body, method = 'GET', auth = asRecruiter, extraHeaders = {}, retried = false) {
  const response = await portal(`/api/v1${path}`, { method, headers: { ...auth, ...extraHeaders, ...(body instanceof FormData ? {} : { 'Content-Type': 'application/json' }), ...(method === 'POST' ? { 'Idempotency-Key': `${tag}:${createHash('sha256').update(path + (body instanceof FormData ? body.get('email') : JSON.stringify(body))).digest('hex')}` } : {}) }, body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body) });
  const text = await response.text();
  if (response.status === 401 && !retried && authRoles.has(auth)) {
    Object.assign(auth, await login(authRoles.get(auth)));
    return api(path, body, method, auth, extraHeaders, true);
  }
  assert(response.ok, `${method} ${path.split('?')[0]} failed (${response.status}): ${text.slice(0, 220)}`);
  return text ? JSON.parse(text) : null;
}
let manifest = { tenantId, tag, jobs: [], applications: [], offers: [], interviews: [], completed: [] };
const previous = mongo(`print(JSON.stringify(db.demo_seed_runs.findOne({_id:${JSON.stringify(tag)},tenantId:${JSON.stringify(tenantId)}})))`);
assert(!previous || resume, 'This seed was already started. Use --resume with its original demo password; no duplicate records were created');
if (previous) { manifest = previous; delete manifest._id; }
function checkpoint() {
  mongo(`const m=${JSON.stringify(manifest)};db.demo_seed_runs.updateOne({_id:${JSON.stringify(tag)},tenantId:m.tenantId},{$set:m},{upsert:true});print(JSON.stringify({ok:true}))`);
}
checkpoint();
const originalRecruiter = existingMembers.find(u => !u.email.startsWith('demo.') && u.status === 'ACTIVE' && u.roles.includes('RECRUITER'));
if (retireCompliance) {
  const admin = await login('ORG_ADMIN');
  const definitions = await api('/roles', undefined, 'GET', admin);
  assert(!definitions.some(role => role.name === 'COMPLIANCE_OFFICER'), 'Deploy identity-service role retirement before disabling demo accounts');
  const target = existingMembers.find(u => u.email === `demo.compliance-officer@${slug}.example.test`);
  assert(target && target.roles.length === 1 && target.roles[0] === 'COMPLIANCE_OFFICER', 'Expected compliance-only demo account; mixed-role accounts are not disabled');
  if (target.status !== 'DISABLED') await api(`/users/${target._id}`, undefined, 'DELETE', admin);
  const users = await api('/users', undefined, 'GET', admin);
  assert(users.find(u => u.id === target._id)?.status === 'DISABLED', 'Demo account was not disabled');
  for (const path of ['/compliance/overview', '/ai-decisions', '/consent-texts', '/retention-policies']) await api(path, undefined, 'GET', admin);
  console.log(JSON.stringify({ retiredRole: 'COMPLIANCE_OFFICER', disabledDemoAccount: target.email, retainedAuditHistory: true, usersPromoted: 0, adminCompliancePagesVerified: 4 }));
  process.exit(0);
}
if (selfServiceRefresh) {
  assert(previous, 'Original tenant demo seed required');
  const { refreshSelfServiceDemo } = await import('./refresh-self-service-demo.mjs');
  const result = await refreshSelfServiceDemo({ api, people, asEmployee: await login('EMPLOYEE'), asRecruiter, manifest, checkpoint, tenantId, slug, tenantName: tenant.name });
  console.log(JSON.stringify({ selfServiceRefresh: result, employeeLogin: `${origin}/internal-jobs`, preservedExistingAccounts: true, candidateLoginAccountsCreated: 0 }));
  process.exit(0);
}
if (agencyRefresh) {
  assert(previous, 'The original tenant demo seed must exist before adding agency fixtures');
  const { refreshAgencyDemo } = await import('./refresh-agency-demo.mjs');
  const result = await refreshAgencyDemo({ api, people, asRecruiter, asAgency: await login('AGENCY_RECRUITER'), manifest, checkpoint, tenantId, slug, tenantName: tenant.name });
  console.log(JSON.stringify({ agencyRefresh: result, login: `${origin}/partner/jobs`, preservedExistingAccounts: true }));
  process.exit(0);
}
if (approvalRefresh) {
  assert(previous, 'The original tenant demo seed must exist before adding the approval refresh');
  const { refreshApprovalDemo } = await import('./refresh-approval-demo.mjs');
  await refreshApprovalDemo({ api, people, asRecruiter, asManager, asHr, asOrgAdmin: await login('ORG_ADMIN'), manifest, checkpoint, tenantId, slug, tenantName: tenant.name, originalRecruiter });
  console.log(JSON.stringify({ approvalRefresh: manifest.approvalRefresh, login: `${origin}/approvals`, preservedExistingAccounts: true }));
  process.exit(0);
}
const jobs = [];
const { demoJobCategory } = await import('./job-category-default.mjs');
for (const [label, desired] of [['Backend Engineer', 'OPEN'], ['Data Engineer', 'OPEN'], ['Product Designer', 'DRAFT'], ['Site Reliability Engineer', 'PENDING_APPROVAL']]) {
  let prior = manifest.jobs.find(j => j.label === label);
  if (!prior) {
    const matches = (await api('/jobs')).data.filter(j => j.title === `${prefix} ${label}`);
    assert(matches.length <= 1, 'Ambiguous demo vacancy; refusing to duplicate it');
    if (matches[0]) prior = { id: matches[0].id, label, desired };
  }
  const job = prior ? await api(`/jobs/${prior.id}`) : await api('/jobs', { categoryId: await demoJobCategory(api, await login('ORG_ADMIN')), workMode: 'REMOTE', title: `${prefix} ${label}`, team: 'Demo Platform', level: 'L4', location: 'Remote - demo', salaryBand: { currency: 'INR', min: 20, max: 30 }, headcount: 1, positionType: 'NEW_POSITION', hiringReason: 'Synthetic local demo requisition; no real hiring commitment.', qualification: 'Relevant degree or equivalent practical experience (synthetic demo)', goodToHaveSkills: ['Microsoft Azure'], mustHaveSkills: ['Java', 'Spring Boot', 'MongoDB'], knockoutQuestions: [], rubric: [{ id: 'backend', label: 'Backend engineering', weight: 60 }, { id: 'communication', label: 'Communication', weight: 40 }], description: 'Synthetic demo vacancy for local testing only. Not a real job advertisement.', status: 'DRAFT' }, 'POST');
  if (!manifest.jobs.some(j => j.id === job.id)) { manifest.jobs.push({ id: job.id, label, desired }); checkpoint(); }
  if (job.status === desired && prior) { jobs.push({ ...job, desired }); continue; }
  if (job.status === 'DRAFT') {
  await api(`/jobs/${job.id}/versions/${job.currentVersion.version}/interview-plan`, { interviewPlan: [{ id: 'technical', name: 'Demo technical interview', durationMin: 45, interviewerNames: [people.INTERVIEWER.username] }, { id: 'manager', name: 'Demo hiring discussion', durationMin: 30, interviewerNames: [people.HIRING_MANAGER.username] }] }, 'PUT');
  const hiringTeam = [{ userId: originalRecruiter?._id || people.RECRUITER.id, name: originalRecruiter?.displayName || 'Demo recruiter', role: 'RECRUITER' }, { userId: people.HIRING_MANAGER.id, name: 'Demo hiring manager', role: 'HIRING_MANAGER' }];
  const latest = await api(`/jobs/${job.id}`);
  await api(`/jobs/${job.id}/hiring-team`, { hiringTeam }, 'PUT', asRecruiter, { 'If-Match': String(latest.currentVersion.version) });
  }
  if (desired !== 'DRAFT') {
    if (job.status === 'DRAFT') await api(`/jobs/${job.id}/submit`, undefined, 'POST');
    if (desired === 'OPEN') {
      const approval = (await api('/approvals?scope=waiting_on_me')).data.find(a => a.title === job.title);
      assert(approval, 'Submitted demo job approval missing');
      await api(`/approvals/${approval.id}/approve`, undefined, 'POST');
      await api(`/jobs/${job.id}/publish`, undefined, 'POST');
    }
  }
  jobs.push({ ...job, desired }); console.log(`PASS: ${label} (${desired})`);
}
// Leave a real pending request assigned to the tenant's existing recruiter, without touching other approvals.
if (!manifest.completed.includes('approvals')) await api('/approvals', { type: 'HEADCOUNT_EXCEPTION', subjectId: jobs[0].id, title: jobs[0].title, steps: [{ id: 'seed-review', approver: originalRecruiter?._id || people.RECRUITER.id, role: 'RECRUITER', group: 0 }], headcountException: { jobTitle: jobs[0].title, currentHeadcount: 1, requestedHeadcount: 2, reason: 'Synthetic additional seat for approval testing only; no real headcount change.' } }, 'POST');
manifest.completed = [...new Set([...manifest.completed, 'jobs', 'approvals'])]; checkpoint();
const consent = await api(`/public/consent?tenantId=${tenantId}`);
for (let index = 0; index < 8; index++) {
  const email = index === 0 ? `demo.candidate@${slug}.example.test` : index === 1 ? people.EMPLOYEE.username : `demo.candidate-${index + 1}@${slug}.example.test`;
  if (manifest.applications.some(a => a.email === email)) continue;
  const name = `${tenant.name} Demo Candidate ${index + 1}`;
  const form = new FormData();
  for (const [key, value] of Object.entries({ tenantId, jobId: jobs[index % 2].id, name, email, processingConsent: 'true', consentVersion: consent.version, receiptToken: randomUUID() + randomUUID(), answers: '{}' })) form.set(key, value);
  form.set('resume', new Blob([resumePdf([`${name} - SYNTHETIC LOCAL TEST DATA`, 'Backend Engineer - 5 years of experience.', 'Skills: Java, Spring Boot, MongoDB, Kafka, SQL, Docker.', 'Built and tested REST APIs with tenant isolation and automated testing.', 'Led backend delivery and collaborated with product teams.', 'Education: BSc Computer Science. This is not a real person or real employment history.'])], { type: 'application/pdf' }), 'demo-resume.pdf');
  const app = await api('/public/applications', form, 'POST', {});
  manifest.applications.push({ id: app.id, name, email, jobId: jobs[index % 2].id }); checkpoint();
  console.log(`PASS: submitted synthetic candidate ${index + 1}`);
}
manifest.completed = [...new Set([...manifest.completed, 'candidate-intake'])]; checkpoint();
console.log('Core seed complete. Resume/AI, talent, notification, compliance and analytics projections are produced by the normal asynchronous workers.');
if (resume) {
  const { enrichDemo } = await import('./enrich-tenant-demo.mjs');
  await enrichDemo({ api, people, asRecruiter, asManager, asHr, asInterviewer, manifest, checkpoint, tenantId, slug });
}
console.log(JSON.stringify({ manifest, next: manifest.completed.includes('workflow-enrichment') ? 'Open the tenant portal to review the synthetic workflows.' : 'Run again with --data-only --resume and the original demo password to enrich the workflows after screening.', preservedExistingAccounts: true, login: `${origin}/login` }));
