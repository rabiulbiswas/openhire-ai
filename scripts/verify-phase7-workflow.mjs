// Local-only Phase 7 acceptance: audit ledger and hash chain, decision trace from a human override to AI evidence,
// consent texts, self-identification and fairness, privacy export, legal hold, retention dry run, multi-service erasure,
// analytics reconciliation, platform health and SLOs, dead-letter inspection and safeguarded replay.
// Needs the full stack (phases 3 to 7) and the local model server (Ollama) for the AI assessments.
// Creates labelled development records and temporary Keycloak users, removed at the end.
//   OPENHIRE_POISON_TOPIC   topic that receives the deliberately malformed message (default application.events.v1)
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { staffToken } from './phase3-staff-token.mjs';
import { resumePdf } from './phase3-pdf-fixture.mjs';
import { strongResume, twinResume, weakResume } from './phase6-resumes.mjs';
import { checkPhase7Dependencies } from './phase7-preflight.mjs';

const gateway = process.env.OPENHIRE_GATEWAY_URL || 'http://localhost:8080';
const keycloak = process.env.OPENHIRE_KEYCLOAK_URL || 'http://localhost:8180';
const mailpit = process.env.OPENHIRE_MAILPIT_URL || 'http://localhost:8025';
const tenant = process.env.OPENHIRE_TENANT_ID || 'demo-org';
assert(['localhost', '127.0.0.1'].includes(new URL(keycloak).hostname), 'Fixture provisioning is local-only');
checkPhase7Dependencies({ gateway, keycloak, mailpit });
const run = randomUUID().slice(0, 8);
const seed = JSON.parse(readFileSync(new URL('../infra/keycloak/openhire-staff-realm.json', import.meta.url)));
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const pass = text => console.log(`PASS: ${text}`);
const info = text => console.log(`INFO: ${text}`);
const norm = text => String(text).toLowerCase().replace(/\s+/g, ' ').trim();

const credentials = new WeakMap();

/** Signs in; the returned headers object is refreshed in place when its token expires during long waits. */
async function login(username, password) {
  const headers = {};
  credentials.set(headers, { username, password });
  await refresh(headers);
  return headers;
}

async function refresh(headers) {
  const { username, password } = credentials.get(headers);
  process.env.OPENHIRE_TEST_USERNAME = username;
  process.env.OPENHIRE_TEST_PASSWORD = password;
  headers.Authorization = `Bearer ${await staffToken()}`;
  const res = await fetch(`${gateway}/bff/csrf`, { headers: { Authorization: headers.Authorization } });
  assert.equal(res.status, 200);
  headers.Cookie = res.headers.getSetCookie().map(v => v.split(';')[0]).join('; ');
  headers['X-XSRF-TOKEN'] = decodeURIComponent(res.headers.getSetCookie().find(v => v.startsWith('XSRF-TOKEN=')).split(';')[0].slice(11));
}

async function api(path, headers, body, method = 'GET', expected = 200, retried = false) {
  const json = body && !(body instanceof FormData);
  const res = await fetch(`${gateway}/api/v1${path}`, {
    method,
    headers: { ...headers, ...(json ? { 'Content-Type': 'application/json' } : {}) },
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(120000),
  });
  const text = await res.text();
  const expectedList = Array.isArray(expected) ? expected : [expected];
  if (res.status === 401 && !retried && credentials.has(headers) && !expectedList.includes(401)) {
    await refresh(headers);
    return api(path, headers, body, method, expected, true);
  }
  assert(expectedList.includes(res.status), `${method} ${path}: ${res.status} ${text.slice(0, 400)}`);
  return text ? JSON.parse(text) : null;
}

async function until(label, probe, timeoutMs = 120000, every = 3000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await probe();
      if (last) return last;
    } catch (error) {
      last = error;
    }
    await sleep(every);
  }
  throw new Error(`Timed out waiting for ${label}: ${last instanceof Error ? last.message : JSON.stringify(last)}`);
}

async function mailTo(address, subjectPart, timeoutMs = 90000) {
  return until(`email "${subjectPart}" to ${address}`, async () => {
    const res = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:"${address}"`)}`);
    return ((await res.json()).messages ?? []).find(message => message.Subject.includes(subjectPart));
  }, timeoutMs, 2000);
}


// ---- Fixtures --------------------------------------------------------------------------------------------------
async function adminHeaders() {
  const response = await fetch(`${keycloak}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: process.env.KEYCLOAK_ADMIN_USERNAME || 'admin', password: process.env.KEYCLOAK_ADMIN_PASSWORD || 'admin' }),
  });
  assert.equal(response.status, 200);
  return { Authorization: `Bearer ${(await response.json()).access_token}`, 'Content-Type': 'application/json' };
}
const createdUsers = [];

async function role(admin, name) {
  const res = await fetch(`${keycloak}/admin/realms/openhire-staff/roles/${name}`, { headers: admin });
  assert.equal(res.status, 200, `realm role ${name}`);
  return res.json();
}

async function fixtureUser(label, roleName) {
  const username = `phase7-${label}-${run}@example.test`;
  const password = `${randomUUID()}aA1!`;
  const admin = await adminHeaders();
  const res = await fetch(`${keycloak}/admin/realms/openhire-staff/users`, {
    method: 'POST', headers: admin,
    body: JSON.stringify({ username, email: username, firstName: label.toUpperCase(), lastName: run, enabled: true, emailVerified: true, attributes: { organization_id: [tenant] }, credentials: [{ type: 'password', value: password, temporary: false }] }),
  });
  assert.equal(res.status, 201, await res.clone().text());
  const id = res.headers.get('location').split('/').pop();
  createdUsers.push(id);
  assert.equal((await fetch(`${keycloak}/admin/realms/openhire-staff/users/${id}/role-mappings/realm`, { method: 'POST', headers: admin, body: JSON.stringify([await role(admin, roleName)]) })).status, 204);
  return login(username, password);
}

async function publishedJob(recruiter, title) {
  const job = await api('/jobs', { ...recruiter, 'Idempotency-Key': randomUUID() }, {
    title, team: 'Platform', level: 'L4', location: 'Pune', salaryBand: { currency: 'INR', min: 20, max: 30 },
    mustHaveSkills: ['Java', 'Kafka'], knockoutQuestions: [],
    rubric: [
      { id: 'c1', label: 'Backend engineering in Java and Spring Boot', weight: 40 },
      { id: 'c2', label: 'Event-driven systems and Kafka', weight: 35 },
      { id: 'c3', label: 'Leading a team and owning production services', weight: 25 },
    ],
    description: 'Phase 7 verification job. Backend engineers building event-driven payment systems in Java.', status: 'DRAFT',
  }, 'POST', [200, 201]);
  await api(`/jobs/${job.id}/versions/${job.currentVersion.version}/interview-plan`, recruiter, { interviewPlan: [{ id: 'p1', name: 'Technical', durationMin: 60, interviewerNames: [] }] }, 'PUT');
  await api(`/jobs/${job.id}/submit`, recruiter, undefined, 'POST');
  const approval = (await api('/approvals?scope=waiting_on_me', recruiter)).data.find(item => item.title === title);
  await api(`/approvals/${approval.id}/approve`, recruiter, undefined, 'POST', [200, 204]);
  await api(`/jobs/${job.id}/publish`, recruiter, undefined, 'POST');
  return job;
}

async function apply(jobId, lines, name, email) {
  const consent = await api(`/public/consent?tenantId=${tenant}`, {});
  const receipt = randomUUID() + randomUUID();
  const form = new FormData();
  for (const [k, v] of Object.entries({ tenantId: tenant, jobId, name, email, phone: '+919800000007', processingConsent: 'true', consentVersion: consent.version, receiptToken: receipt, answers: '{}' })) form.set(k, v);
  form.set('resume', new Blob([resumePdf(lines)], { type: 'application/pdf' }), 'phase7.pdf');
  const application = await api('/public/applications', { 'Idempotency-Key': randomUUID() }, form, 'POST');
  return { id: application.id, receipt, name, email, text: norm(lines.join(' ')) };
}

const publicHeaders = app => ({ 'X-Application-Receipt': app.receipt });
const stageOf = app => api(`/public/applications/${app.id}?tenantId=${tenant}`, publicHeaders(app)).then(view => view.stage);
const auditOf = (staff, query) => api(`/audit-events?${query}&limit=200`, staff).then(page => page.data);

function completeAssessment(recruiter, app, timeoutMs = 360000) {
  return until(`COMPLETE assessment for ${app.name}`, async () => {
    const assessment = await api(`/applications/${app.id}/assessment`, recruiter, undefined, 'GET', [200, 404]);
    return assessment?.status === 'COMPLETE' ? assessment : null;
  }, timeoutMs, 4000);
}

async function privacyRequest(app, type) {
  const created = await api(`/public/applications/${app.id}/privacy-requests?tenantId=${tenant}`, { ...publicHeaders(app) }, { type }, 'POST', [200, 201]);
  return created;
}

const completedRequest = (officer, id, timeoutMs = 180000) => until(`privacy request ${id} COMPLETED`, async () => {
  const request = await api(`/privacy/requests/${id}`, officer);
  if (request.status === 'FAILED') throw new Error(`request failed: ${JSON.stringify(request)}`);
  return request.status === 'COMPLETED' ? request : null;
}, timeoutMs, 3000);

try {
  const recruiterSeed = seed.users.find(u => u.username === 'recruiter@openhire.local');
  const adminSeed = seed.users.find(u => u.username === 'admin@openhire.local');
  const secondSeed = seed.users.find(u => u.username === 'admin@second.openhire.local');
  const recruiter = await login(recruiterSeed.username, recruiterSeed.credentials[0].value);
  const admin = await login(adminSeed.username, adminSeed.credentials[0].value);
  const otherTenantAdmin = await login(secondSeed.username, secondSeed.credentials[0].value);
  const officer = await fixtureUser('officer', 'COMPLIANCE_OFFICER');
  const superAdmin = await fixtureUser('super', 'SUPER_ADMIN');
  pass('sessions: recruiter, org admin, compliance officer, super admin and an administrator of a second organisation');

  await api('/audit-events', recruiter, undefined, 'GET', 403);
  await api('/compliance/overview', recruiter, undefined, 'GET', 403);
  await api('/platform/services', admin, undefined, 'GET', 403);
  pass('role boundaries: recruiters cannot read audit or compliance data; org admins cannot read platform services');

  // ---- Journeys -----------------------------------------------------------------------------------------------------
  const job = await publishedJob(recruiter, `Phase 7 platform engineer ${run}`);
  const anika = await apply(job.id, strongResume(`Anika Rao ${run}`), `Anika Rao ${run}`, `p7-anika-${run}@example.test`);
  const rohan = await apply(job.id, twinResume(`Rohan Das ${run}`), `Rohan Das ${run}`, `p7-rohan-${run}@example.test`);
  const meera = await apply(job.id, weakResume(`Meera Pillai ${run}`), `Meera Pillai ${run}`, `p7-meera-${run}@example.test`);
  const dev = await apply(job.id, weakResume(`Dev Malhotra ${run}`), `Dev Malhotra ${run}`, `p7-dev-${run}@example.test`);
  const farah = await apply(job.id, weakResume(`Farah Khan ${run}`), `Farah Khan ${run}`, `p7-farah-${run}@example.test`);
  for (const app of [anika, rohan, meera, dev, farah]) {
    await until(`rules screening of ${app.name}`, async () => (await stageOf(app)) === 'AI_ASSESSED', 180000);
    app.candidateId = await until(`candidate id for ${app.name}`, async () => {
      const cards = await api(`/jobs/${job.id}/applications`, recruiter);
      return (cards.data ?? cards).find(card => (card.id ?? card.applicationId) === app.id)?.candidateId;
    }, 90000, 2000);
  }
  pass('five applications submitted and screened');

  const aAnika = await completeAssessment(recruiter, anika);
  await completeAssessment(recruiter, meera);
  pass(`AI assessments complete (strong candidate scored ${aAnika.score})`);

  await api(`/assessments/${anika.id}/override`, recruiter, { newScore: Math.min(100, aAnika.score + 5), reason: 'Leadership evidence sits in a section the model weighted lightly', reasonCode: 'UNDERWEIGHTED_EVIDENCE' }, 'POST', [200, 204]);
  await api(`/applications/${anika.id}/shortlist`, recruiter, { reason: 'Strong technical signal' }, 'POST', [200, 204]);
  await api(`/applications/${dev.id}/reject`, recruiter, { reason: 'Role requirements not met', reasonCode: 'SKILLS_MISMATCH' }, 'POST', [200, 204]);
  await api(`/applications/${farah.id}/reject`, recruiter, { reason: 'Role requirements not met', reasonCode: 'SKILLS_MISMATCH' }, 'POST', [200, 204]);
  pass('a person overrode the AI score with a reason code, shortlisted one candidate and rejected two');

  // ---- Audit ledger -----------------------------------------------------------------------------------------------------
  const wanted = ['APPLICATION_SHORTLISTED', 'APPLICATION_REJECTED', 'JOB_CREATED', 'JOB_PUBLISHED'];
  const seen = await until('audit records for the actions above', async () => {
    const events = await auditOf(officer, 'from=2020-01-01T00:00:00Z');
    const actions = new Set(events.map(event => event.action));
    return wanted.every(action => actions.has(action)) && events.some(event => event.action === 'ASSESSMENT_OVERRIDDEN' && event.resource?.id === anika.id) ? events : null;
  }, 90000, 2000);
  const overrideAudit = seen.find(event => event.action === 'ASSESSMENT_OVERRIDDEN' && event.resource?.id === anika.id);
  assert(overrideAudit, 'the score override must be in the audit ledger');
  assert.equal(overrideAudit.details?.reasonCode, 'UNDERWEIGHTED_EVIDENCE');
  assert(!JSON.stringify(seen).includes(anika.email), 'audit records never hold contact details');
  const verified = await api('/audit-events/verify', officer);
  assert.equal(verified.valid, true);
  assert(verified.checked >= seen.length);
  const sequences = (await auditOf(officer, 'from=2020-01-01T00:00:00Z')).map(event => event.seq).sort((a, b) => a - b);
  for (let i = 1; i < sequences.length; i++) assert.equal(sequences[i], sequences[i - 1] + 1, 'the audit sequence has no gaps');
  info(`audit actions seen: ${[...new Set(seen.map(event => event.action))].sort().join(', ')}`);
  pass(`audit ledger: ${verified.checked} records, hash chain valid, gapless sequence, override recorded with its reason code, no contact details`);

  const isolated = await api('/audit-events?limit=200', otherTenantAdmin);
  assert(!isolated.data.some(event => event.applicationId === anika.id), 'another organisation must not see these records');
  const foreignTrace = await api(`/trace/applications/${anika.id}`, otherTenantAdmin, undefined, 'GET', [200, 403, 404]);
  assert(!Array.isArray(foreignTrace) || foreignTrace.length === 0, 'another organisation must get an empty trace');
  pass('tenant isolation: the second organisation sees none of these audit records and gets nothing from the trace');

  // ---- Decision trace: UI action -> AI evidence -> model and audit records ------------------------------------------------
  const trace = await until('a complete decision trace', async () => {
    const entries = await api(`/trace/applications/${anika.id}`, officer);
    const kinds = new Set(entries.map(entry => entry.kind));
    return ['AUDIT', 'AI', 'STAGE'].every(kind => kinds.has(kind)) ? entries : null;
  }, 90000, 2000);
  for (let i = 1; i < trace.length; i++) assert(trace[i].at >= trace[i - 1].at, 'trace is ordered by time');
  const decisions = await until('AI decision history', async () => {
    const list = await api(`/ai-decisions?applicationId=${anika.id}`, officer);
    return list.length && list[0].humanAction ? list : null;
  }, 90000, 2000);
  const decision = await api(`/ai-decisions/${decisions[0].id}`, officer);
  assert(decision.model && decision.promptVersion, 'model and prompt version are recorded');
  assert(decisions[0].humanAction.by && decisions[0].humanAction.action, 'the latest human action is named');
  const overrideAction = (decision.humanActions ?? []).find(action => /overrid/i.test(action.action));
  assert(overrideAction, `the override is among the human actions: ${JSON.stringify(decision.humanActions)}`);
  assert.equal(overrideAction.reasonCode, 'UNDERWEIGHTED_EVIDENCE');
  const quotes = decision.criteria.flatMap(criterion => (criterion.evidence ?? []).map(evidence => evidence.quote ?? evidence));
  assert(quotes.length >= 2, 'evidence quotes are present');
  for (const quote of quotes) assert(anika.text.includes(norm(quote)), `ungrounded quote in the history: "${quote}"`);
  assert(decisions[0].candidateName?.includes('Anika'), 'names are resolved at query time');
  info(`trace: ${trace.length} entries (${[...new Set(trace.map(entry => entry.kind))].join(', ')}); model ${decision.model}, prompt ${decision.promptVersion}`);
  pass('decision trace: one ordered timeline from the human override to the AI criteria, grounded evidence quotes, model, prompt version and audit records');

  // ---- Consent texts ----------------------------------------------------------------------------------------------------
  const before = await api(`/public/consent?tenantId=${tenant}`, {});
  const published = await api('/consent-texts', officer, { purpose: 'PROCESSING', locale: 'en', text: `We process your application for this role and keep it for 24 months. Verification ${run}.` }, 'POST', [200, 201]);
  assert.notEqual(published.version, before.version);
  const texts = await api('/consent-texts', officer);
  assert.equal(texts.filter(text => text.purpose === 'PROCESSING' && text.status === 'ACTIVE').length, 1, 'exactly one active version');
  await until('the candidate form to serve the new version', async () => (await api(`/public/consent?tenantId=${tenant}`, {})).version === published.version, 120000, 5000);
  const stale = new FormData();
  for (const [k, v] of Object.entries({ tenantId: tenant, jobId: job.id, name: `Stale ${run}`, email: `p7-stale-${run}@example.test`, phone: '+919800000007', processingConsent: 'true', consentVersion: before.version, receiptToken: randomUUID() + randomUUID(), answers: '{}' })) stale.set(k, v);
  stale.set('resume', new Blob([resumePdf(weakResume('Stale'))], { type: 'application/pdf' }), 'phase7.pdf');
  await api('/public/applications', { 'Idempotency-Key': randomUUID() }, stale, 'POST', [400, 409, 422]);
  pass(`consent text ${published.version} published and served; a submission against the old version is refused; one version active`);

  // ---- Self-identification and fairness ----------------------------------------------------------------------------------
  for (const [app, gender] of [[anika, 'FEMALE'], [rohan, 'MALE'], [meera, 'FEMALE'], [dev, 'MALE'], [farah, 'PREFER_NOT_TO_SAY']])
    await api(`/public/applications/${app.id}/self-identification?tenantId=${tenant}`, publicHeaders(app), { gender }, 'PUT', [200, 204]);
  const own = await api(`/public/applications/${anika.id}/self-identification?tenantId=${tenant}`, publicHeaders(anika));
  assert.equal(own.gender, 'FEMALE');
  await api(`/public/applications/${anika.id}/self-identification?tenantId=${tenant}`, {}, undefined, 'GET', [400, 401, 403]);
  const fairness = await until('a fairness report', async () => {
    const report = await api(`/reports/fairness?jobId=${job.id}&dimension=gender`, officer);
    return report.transitions?.length ? report : null;
  }, 60000, 3000);
  assert.equal(fairness.minGroupSize, 5);
  assert.equal(fairness.adverseImpactThreshold, 0.8);
  const groups = fairness.transitions.flatMap(transition => transition.groups);
  assert(groups.every(group => group.n >= 5 || group.insufficientData === true), 'small groups are marked insufficient');
  assert(groups.every(group => !(group.insufficientData && group.flag)), 'small groups are never flagged');
  assert(!JSON.stringify(fairness).includes(anika.candidateId), 'the report is aggregate only');
  await api(`/reports/fairness?jobId=${job.id}&dimension=gender`, recruiter, undefined, 'GET', 403);
  pass('self-identification stored and readable only with the receipt; fairness report is aggregate, flags nothing below the minimum group size, refused to recruiters');

  // ---- Export ------------------------------------------------------------------------------------------------------------
  const exportRequest = await privacyRequest(rohan, 'EXPORT');
  const done = await completedRequest(officer, exportRequest.id);
  const bundle = await until('the export to be available', async () => {
    const res = await fetch(`${gateway}/api/v1/public/privacy-requests/${exportRequest.id}/export?tenantId=${tenant}`, { headers: publicHeaders(rohan) });
    return res.status === 200 ? res.json() : null;
  }, 60000, 2000);
  const sections = Object.keys(bundle.sections);
  for (const service of ['application-service', 'candidate-service', 'resume-processor', 'ai-workers'])
    assert(sections.includes(service), `export section from ${service}`);
  const exported = JSON.stringify(bundle);
  assert(exported.includes('Rohan Das'), 'the candidate sees their own data');
  assert(!/"[A-Za-z]*(hash|secret|token|password|salt)[A-Za-z]*"\s*:/i.test(exported), 'no internal hashes or secrets in an export');
  assert(!exported.includes(anika.email), 'nobody else is in the export');
  const wrong = await fetch(`${gateway}/api/v1/public/privacy-requests/${exportRequest.id}/export?tenantId=${tenant}`, { headers: { 'X-Application-Receipt': anika.receipt } });
  assert([403, 404].includes(wrong.status), `another candidate's receipt must be refused (${wrong.status})`);
  assert.equal(done.status, 'COMPLETED');
  pass(`export assembled from ${sections.length} services for the candidate only; no secrets; another candidate's receipt refused`);

  // ---- Legal hold, retention dry run, erasure -------------------------------------------------------------------------------
  const hold = await api('/legal-holds', officer, { candidateId: dev.candidateId, reason: 'Open complaint, phase 7 verification' }, 'POST', [200, 201]);
  const retention = await api(`/retention-policies/run?dryRun=true&asOf=${new Date(Date.now() + 5 * 366 * 86400000).toISOString()}`, officer, undefined, 'POST');
  assert.equal(retention.dryRun, true);
  const nonHired = retention.policies.find(policy => policy.key === 'candidate.non-hired');
  assert(nonHired, 'the non-hired policy is evaluated');
  assert(nonHired.wouldErase.some(item => item.candidateId === farah.candidateId), 'a rejected candidate is due for erasure five years on');
  assert(!nonHired.wouldErase.some(item => item.candidateId === dev.candidateId), 'a held candidate is not listed for erasure');
  assert(nonHired.protectedByHold.some(item => item.candidateId === dev.candidateId && item.holdId === hold.id), 'the hold is named as the protection');
  for (const app of [anika, rohan, meera]) assert(!nonHired.wouldErase.some(item => item.candidateId === app.candidateId), 'candidates with an application in progress are not listed');
  assert.equal((await stageOf(farah)), 'REJECTED', 'a dry run changes nothing');
  pass('retention dry run, evaluated five years on: the rejected candidate is due, the held candidate is protected by the named hold, in-progress candidates are left alone');

  const blocked = await api('/privacy/requests', officer, { candidateId: dev.candidateId, type: 'ERASURE', reason: 'Candidate asked by phone' }, 'POST', [200, 201]);
  assert.equal(blocked.status, 'BLOCKED_BY_LEGAL_HOLD');
  await new Promise(resolve => setTimeout(resolve, 8000));
  assert.equal((await api(`/privacy/requests/${blocked.id}`, officer)).status, 'BLOCKED_BY_LEGAL_HOLD', 'nothing is erased while the hold stands');
  await api(`/legal-holds/${hold.id}/release`, officer, { reason: 'Complaint closed' }, 'POST', [200, 204]);
  const released = await completedRequest(officer, blocked.id);
  assert.equal(released.status, 'COMPLETED');
  pass('erasure requested under a legal hold stays blocked; releasing the hold lets it complete');

  const erasure = await privacyRequest(meera, 'ERASURE');
  const finished = await completedRequest(officer, erasure.id);
  const expected = ['application-service', 'candidate-service', 'resume-processor', 'communication-service', 'notification-dispatcher', 'interview-service', 'offer-service', 'employee-service', 'talent-service', 'ai-workers'];
  for (const service of expected) assert(finished.participants?.[service]?.status, `acknowledgement from ${service}`);
  const evidenceReport = await api(`/privacy/requests/${erasure.id}`, officer);
  assert(evidenceReport.evidenceHash && evidenceReport.evidenceHash.length >= 32, 'an evidence hash is recorded');
  assert(!JSON.stringify(evidenceReport).includes(meera.candidateId), `the request keeps only a hash of the candidate id: ${(() => { const t = JSON.stringify(evidenceReport); const i = t.indexOf(meera.candidateId); return t.slice(Math.max(0, i - 120), i + 100); })()}`);
  assert.equal(evidenceReport.stuck, false);
  const erased = await api(`/applications/${meera.id}`, recruiter, undefined, 'GET', [200, 404, 410]);
  if (erased) {
    const text = JSON.stringify(erased);
    assert(!text.includes(meera.email) && !text.includes('Meera Pillai'), 'the application no longer holds the candidate identity');
  }
  const gone = await api(`/applications/${meera.id}/assessment`, recruiter, undefined, 'GET', [200, 404, 410]);
  if (gone) assert(!JSON.stringify(gone.criteria ?? []).includes('Kochi'), 'assessment evidence is erased');
  const afterAudit = await auditOf(officer, `candidateId=${meera.candidateId}`);
  assert(afterAudit.length === 0 || afterAudit.every(event => !JSON.stringify(event).includes(meera.email)), 'audit records never carried contact details');
  const completion = await until('the completion audit record', async () => (await auditOf(officer, 'action=PRIVACY_REQUEST_COMPLETED')).find(event => event.resource?.id === erasure.id));
  assert(completion);
  assert.equal((await api('/audit-events/verify', officer)).valid, true, 'erasing personal data does not break the audit chain');
  const resubmit = await api(`/public/applications/${meera.id}?tenantId=${tenant}`, publicHeaders(meera), undefined, 'GET', [200, 404, 410]);
  if (resubmit) assert(!JSON.stringify(resubmit).includes(meera.email));
  info(`erasure acknowledgements: ${expected.map(service => `${service}=${finished.participants[service].status}`).join(', ')}`);
  pass('controlled erasure: all ten services acknowledged, evidence hash recorded, identity gone from application and assessment data, audit chain still valid');

  // ---- Analytics ------------------------------------------------------------------------------------------------------------
  const funnel = await until('the analytics funnel', async () => {
    const result = await api(`/analytics/funnel?range=30d&jobId=${job.id}`, admin);
    return result.stages?.find(stage => stage.label === 'Applied')?.value >= 5 ? result : null;
  }, 90000, 3000);
  const labels = funnel.stages.map(stage => stage.label);
  assert.deepEqual(labels, ['Applied', 'AI assessed', 'Shortlisted', 'Interviewing', 'Offer', 'Hired']);
  for (let i = 1; i < funnel.stages.length; i++) assert(funnel.stages[i].value <= funnel.stages[i - 1].value, 'a funnel only narrows');
  assert.equal(funnel.stages[0].value, 5, 'reconciles with the five applications submitted for this job');
  assert(funnel.stages[2].value >= 1, 'the shortlisted candidate is counted');
  const kpis = await api('/analytics/kpis?range=30d', admin);
  assert(kpis && typeof kpis === 'object');
  for (const path of ['time-to-hire', 'sources', 'ai-overrides', 'stage-latency']) assert(Array.isArray(await api(`/analytics/${path}?range=90d`, admin)), path);
  const overrides = await until('the override in analytics', async () => (await api('/analytics/ai-overrides?range=30d', admin)).find(item => /underweight/i.test(item.label)));
  assert(overrides.value >= 1);
  await api('/analytics/kpis?range=30d', recruiter, undefined, 'GET', [200, 403]);
  pass('analytics reconcile with the seeded journeys: five applied, shortlisted counted, funnel only narrows, the override appears by reason');

  // ---- Platform health and SLOs -----------------------------------------------------------------------------------------------
  const services = await until('platform service health', async () => {
    const list = await api('/platform/services', superAdmin);
    return list.length >= 10 ? list : null;
  }, 120000, 4000);
  assert(services.some(service => service.name.includes('compliance') && service.status === 'UP'));
  const slos = await api('/platform/slo', superAdmin);
  for (const id of ['public-api-availability', 'application-api-latency', 'resume-processing-60s', 'ai-assessment-2m', 'notification-handoff-60s'])
    assert(slos.some(slo => slo.id === id), `SLO ${id}`);
  assert((await api('/platform/slo', admin)).length >= 5, 'org admins can read SLOs');
  const consumers = await api('/platform/consumers', superAdmin);
  assert(consumers.length > 0 && consumers.every(consumer => ['OK', 'LAGGING'].includes(consumer.status)));
  pass(`platform health: ${services.length} services polled, five SLOs with status, ${consumers.length} consumer groups with lag`);

  // ---- Dead letters ----------------------------------------------------------------------------------------------------------
  const poisonTopic = process.env.OPENHIRE_POISON_TOPIC || 'application.events.v1';
  const marker = `phase7-poison-${run}`;
  const poisonedAt = Date.now() - 5000;
  execFileSync('docker', ['exec', '-i', 'openhire-kafka-1', '/opt/kafka/bin/kafka-console-producer.sh', '--bootstrap-server', 'localhost:9092', '--topic', poisonTopic], { input: `{"eventId":"${marker}","tenantId":"${tenant}","eventType":"phase7.poison.v1" this is not valid json\n`, encoding: 'utf8' });
  const letter = await until('a dead letter for the poisoned message', async () => {
    const list = await api('/admin/dlq?limit=100', superAdmin);
    // A body that is not valid JSON is never shown, so the entry is recognised by topic and time.
    return list.find(item => item.originalTopic === poisonTopic && Date.parse(item.firstFailedAt) >= poisonedAt);
  }, 150000, 4000);
  const detail = await api(`/admin/dlq/${encodeURIComponent(letter.id)}`, superAdmin);
  assert(!JSON.stringify(detail).match(/@example\.test/), 'payloads are redacted');
  await api(`/admin/dlq/${encodeURIComponent(letter.id)}/replay`, superAdmin, { reason: '' }, 'POST', 400);
  const replay = await api(`/admin/dlq/${encodeURIComponent(letter.id)}/replay`, superAdmin, { reason: 'Phase 7 verification replay' }, 'POST', [200, 202]);
  assert(replay.replayedAt);
  await api(`/admin/dlq/${encodeURIComponent(letter.id)}/replay`, superAdmin, { reason: 'Phase 7 verification replay again' }, 'POST', 409);
  const forced = await api(`/admin/dlq/${encodeURIComponent(letter.id)}/replay?force=true`, superAdmin, { reason: 'Phase 7 verification forced' }, 'POST', [200, 202]);
  assert(forced.replayCount >= 2, 'a forced second replay by a super admin is counted');
  await api(`/admin/dlq/${encodeURIComponent(letter.id)}/replay?force=true`, admin, { reason: 'Phase 7 verification forced' }, 'POST', [403, 404, 409]);
  assert(!(await api('/admin/dlq?limit=100', admin)).some(item => item.id === letter.id), 'a message with no known organisation is visible to super admins only');
  const dryRun = await api('/admin/dlq/replay', superAdmin, { reason: 'Count what is waiting', limit: 5, dryRun: true }, 'POST');
  assert.equal(dryRun.dryRun, true);
  assert.equal(dryRun.replayed, 0);
  await until('the replay audit record', async () => (await auditOf(officer, 'action=DEAD_LETTER_REPLAYED')).find(event => event.resource?.id === letter.id));
  assert(!(await api('/admin/dlq?limit=100', otherTenantAdmin)).some(item => item.id === letter.id), 'another organisation cannot see this dead letter');
  pass('dead letters: listed and redacted, a reason is required, one replay then 409, a forced second replay is super-admin only, dry run counts only, replay audited, organisations never see foreign or unattributed messages');

  console.log(`Phase 7 workflow completed. Development job ${job.id} and its test candidates are retained as test data.`);
} finally {
  const admin = createdUsers.length ? await adminHeaders() : {};
  for (const id of createdUsers) {
    const res = await fetch(`${keycloak}/admin/realms/openhire-staff/users/${id}`, { method: 'DELETE', headers: admin });
    if (res.status !== 204) console.error(`Fixture user cleanup requires review: ${id}`);
  }
}
