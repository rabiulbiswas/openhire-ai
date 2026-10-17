// Local-only Phase 5 acceptance: offers, approvals, candidate response, hire saga, onboarding, rescind, referral reward.
// Creates labelled development records (two jobs, several applications, offers, employees) and temporary Keycloak
// users (hiring manager, HR admin, two employees), which it removes at the end. Set OPENHIRE_TEST_RESTART=true to
// also restart employee-service while a hire saga is pending and prove deterministic recovery.
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { staffToken } from './phase3-staff-token.mjs';
import { resumePdf } from './phase3-pdf-fixture.mjs';

const gateway = process.env.OPENHIRE_GATEWAY_URL || 'http://localhost:8080';
const keycloak = process.env.OPENHIRE_KEYCLOAK_URL || 'http://localhost:8180';
const mailpit = process.env.OPENHIRE_MAILPIT_URL || 'http://localhost:8025';
const tenant = process.env.OPENHIRE_TENANT_ID || 'demo-org';
assert(['localhost', '127.0.0.1'].includes(new URL(keycloak).hostname), 'Fixture provisioning is local-only');
const run = randomUUID().slice(0, 8);
const seed = JSON.parse(readFileSync(new URL('../../infra/keycloak/openhire-staff-realm.json', import.meta.url)));
const backend = new URL('../..', import.meta.url).pathname;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const pass = text => console.log(`PASS: ${text}`);
const day = offset => new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);

async function login(username, password) {
  process.env.OPENHIRE_TEST_USERNAME = username;
  process.env.OPENHIRE_TEST_PASSWORD = password;
  const token = await staffToken();
  const headers = { Authorization: `Bearer ${token}` };
  const res = await fetch(`${gateway}/bff/csrf`, { headers });
  assert.equal(res.status, 200);
  headers.Cookie = res.headers.getSetCookie().map(v => v.split(';')[0]).join('; ');
  headers['X-XSRF-TOKEN'] = decodeURIComponent(res.headers.getSetCookie().find(v => v.startsWith('XSRF-TOKEN=')).split(';')[0].slice(11));
  return headers;
}

async function api(path, headers, body, method = 'GET', expected = 200) {
  const json = body && !(body instanceof FormData);
  const res = await fetch(`${gateway}/api/v1${path}`, {
    method,
    headers: { ...headers, ...(json ? { 'Content-Type': 'application/json' } : {}) },
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(45000),
  });
  const text = await res.text();
  const expectedList = Array.isArray(expected) ? expected : [expected];
  assert(expectedList.includes(res.status), `${method} ${path}: ${res.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

async function until(label, probe, timeoutMs = 60000) {
  const deadline = Date.now() + timeoutMs;
  let last;
  while (Date.now() < deadline) {
    try {
      last = await probe();
      if (last) return last;
    } catch (error) {
      last = error;
    }
    await sleep(1500);
  }
  throw new Error(`Timed out waiting for ${label}: ${last instanceof Error ? last.message : JSON.stringify(last)}`);
}

async function mails(address, subjectPart) {
  const res = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:"${address}"`)}`);
  return ((await res.json()).messages ?? []).filter(message => message.Subject.includes(subjectPart));
}

/** The newest message whose subject matches; waits until at least `count` matching messages exist. */
async function mailBody(address, subjectPart, count = 1) {
  const found = await until(`email "${subjectPart}" to ${address}`, async () => {
    const list = await mails(address, subjectPart);
    return list.length >= count ? list[0] : null;
  });
  return (await (await fetch(`${mailpit}/api/v1/message/${found.ID}`)).json()).Text;
}

const tokenFrom = text => decodeURIComponent(text.match(/#token=([^\s"<>]+)/)[1]);

// ---- Offer-token (public) calls ----------------------------------------------------------------------------
async function pub(path, token, body, method = 'GET', expected = 200) {
  return api(`/public/offers${path}`, { 'X-Offer-Token': token }, body, method, expected);
}

// ---- Fixtures: temporary Keycloak users --------------------------------------------------------------------
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
  let res = await fetch(`${keycloak}/admin/realms/openhire-staff/roles/${name}`, { headers: admin });
  if (res.status === 404) {
    // A realm imported before this phase has no HR_ADMIN role.
    assert.equal((await fetch(`${keycloak}/admin/realms/openhire-staff/roles`, { method: 'POST', headers: admin, body: JSON.stringify({ name }) })).status, 201);
    res = await fetch(`${keycloak}/admin/realms/openhire-staff/roles/${name}`, { headers: admin });
  }
  assert.equal(res.status, 200);
  return res.json();
}

async function fixtureUser(label, roleName, email) {
  const username = email || `phase5-${label}-${run}@example.test`;
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
  return { email: username, headers: await login(username, password) };
}

// ---- Job and candidate helpers -----------------------------------------------------------------------------
async function publishedJob(recruiter, title) {
  const job = await api('/jobs', { ...recruiter, 'Idempotency-Key': randomUUID() }, {
    title, team: 'Platform', level: 'L4', location: 'Bengaluru', salaryBand: { currency: 'INR', min: 20, max: 30 },
    mustHaveSkills: ['Java', 'Spring Boot'], knockoutQuestions: [],
    rubric: [{ id: 'c1', label: 'Problem solving', weight: 60 }, { id: 'c2', label: 'Communication', weight: 40 }],
    description: 'Phase 5 verification job', status: 'DRAFT',
  }, 'POST', [200, 201]);
  await api(`/jobs/${job.id}/versions/${job.currentVersion.version}/interview-plan`, recruiter, { interviewPlan: [{ id: 'p1', name: 'Technical', durationMin: 60, interviewerNames: [] }] }, 'PUT');
  await api(`/jobs/${job.id}/submit`, recruiter, undefined, 'POST');
  const approval = (await api('/approvals?scope=waiting_on_me', recruiter)).data.find(item => item.title === title);
  await api(`/approvals/${approval.id}/approve`, recruiter, undefined, 'POST', [200, 204]);
  await api(`/jobs/${job.id}/publish`, recruiter, undefined, 'POST');
  return job;
}

async function careerApply(jobId, name, email) {
  const consent = await api(`/public/consent?tenantId=${tenant}`, {});
  const receipt = randomUUID() + randomUUID();
  const form = new FormData();
  for (const [k, v] of Object.entries({ tenantId: tenant, jobId, name, email, phone: '+919800000005', processingConsent: 'true', consentVersion: consent.version, receiptToken: receipt, answers: '{}' })) form.set(k, v);
  form.set('resume', new Blob([resumePdf()], { type: 'application/pdf' }), 'phase5-fixture.pdf');
  const application = await api('/public/applications', { 'Idempotency-Key': randomUUID() }, form, 'POST');
  await until(`screening of ${name}`, async () => (await api(`/public/applications/${application.id}?tenantId=${tenant}`, { 'X-Application-Receipt': receipt })).stage === 'AI_ASSESSED', 180000);
  return { id: application.id, receipt, name, email };
}

/** Shortlist, interview and record the human HIRE decision; the application is then at OFFER. */
async function decideHire(recruiter, applicationId) {
  await api(`/applications/${applicationId}/shortlist`, recruiter, undefined, 'POST');
  await api(`/applications/${applicationId}/stage`, recruiter, { stage: 'INTERVIEWING' }, 'PATCH');
  await api(`/applications/${applicationId}/decision`, recruiter, { decision: 'HIRE', reason: 'Phase 5 acceptance: strong signal.' }, 'POST');
}

const stageOf = application => api(`/public/applications/${application.id}?tenantId=${tenant}`, { 'X-Application-Receipt': application.receipt }).then(view => view.stage);

/** Runs the approval chain: HM, HR and (when asked) Finance, each a different person. */
async function approveAll(offerId, people, finance) {
  await api(`/offers/${offerId}/approvals/s1/approve`, people.manager.headers, undefined, 'POST');
  await api(`/offers/${offerId}/approvals/s2/approve`, people.hr.headers, undefined, 'POST');
  if (finance) await api(`/offers/${offerId}/approvals/s3/approve`, people.finance, undefined, 'POST');
  assert.equal((await api(`/offers/${offerId}`, people.recruiter)).status, 'APPROVED');
}

try {
  const recruiterSeed = seed.users.find(u => u.username === 'recruiter@openhire.local');
  const adminSeed = seed.users.find(u => u.username === 'admin@openhire.local');
  const recruiter = await login(recruiterSeed.username, recruiterSeed.credentials[0].value);
  const finance = await login(adminSeed.username, adminSeed.credentials[0].value); // ORG_ADMIN takes the Finance step
  const manager = await fixtureUser('hm', 'HIRING_MANAGER');
  const hr = await fixtureUser('hr', 'HR_ADMIN');
  const people = { recruiter, manager, hr, finance };
  pass('recruiter, hiring manager, HR admin and org admin sessions');

  const jobTitle = `Phase 5 verification engineer ${run}`;
  const job = await publishedJob(recruiter, jobTitle);
  const otherJob = await publishedJob(recruiter, `Phase 5 second role ${run}`);
  pass(`jobs ${job.id} and ${otherJob.id} published with a 20-30 band`);

  // ---- Candidate 1: full journey ---------------------------------------------------------------------------
  const ada = await careerApply(job.id, `Priya Phase${run}`, `phase5-ada-${run}@example.test`);
  const alsoApplied = await careerApply(otherJob.id, ada.name, ada.email); // a second, still-open application
  const early = await careerApply(job.id, `Early Phase${run}`, `phase5-early-${run}@example.test`);
  await api(`/applications/${early.id}/offer`, recruiter, {}, 'POST', 409); // no hire decision yet
  await decideHire(recruiter, ada.id);
  assert.equal(await stageOf(ada), 'OFFER');
  pass('an offer cannot be created before a hire decision; the decision moves the application to OFFER');

  const draft = await api(`/applications/${ada.id}/offer`, recruiter, {}, 'POST', 201);
  assert.equal(draft.status, 'DRAFT');
  assert.equal(draft.terms.base, 25);
  assert.equal(draft.band.max, 30);
  assert.equal(draft.templateName, 'Standard offer letter');
  assert.equal((await api(`/applications/${ada.id}/offer`, recruiter, {}, 'POST', [200, 201])).id, draft.id, 'creating again must return the same offer');
  const tooHigh = await api(`/offers/${draft.id}`, recruiter, { terms: { base: 45 } }, 'PATCH', 400);
  assert.match(tooHigh.detail, /outside the job's band/);
  const noLine = await api(`/offers/${draft.id}/submit`, recruiter, undefined, 'POST', 400);
  assert.match(noLine.detail, /reports to/);
  const edited = await api(`/offers/${draft.id}`, recruiter, { terms: { base: 28, joiningDate: day(30), expiryDate: day(7), reportsTo: 'Meera Nair' }, coverNote: 'We loved meeting you.' }, 'PATCH');
  assert.equal(edited.terms.withinBand, true);
  pass('draft prefilled from the job band; out-of-band base rejected; edits saved');

  await api(`/offers/${draft.id}/send`, recruiter, undefined, 'POST', 409); // not approved yet
  const submitted = await api(`/offers/${draft.id}/submit`, recruiter, undefined, 'POST');
  assert.equal(submitted.status, 'PENDING_APPROVAL');
  assert.deepEqual(submitted.approvalSteps.map(step => step.role), ['HIRING_MANAGER', 'HR_ADMIN', 'FINANCE']);
  assert.match(submitted.approvalNote, /Finance joined/);
  const needed = await until('approval-needed notification for the hiring manager', async () => (await api('/notifications', manager.headers)).data.find(item => item.type === 'APPROVAL_REQUESTED' && item.title === 'Offer approval needed' && item.link.params.offerId === draft.id));
  assert.equal(needed.link.route, 'offers');
  await api(`/offers/${draft.id}/approvals/s1/approve`, recruiter, undefined, 'POST', 400); // the requester has no approver role
  await api(`/offers/${draft.id}/approvals/s2/approve`, hr.headers, undefined, 'POST', 409); // out of order
  await api(`/offers/${draft.id}/approvals/s1/approve`, manager.headers, undefined, 'POST');
  await api(`/offers/${draft.id}/approvals/s1/approve`, manager.headers, undefined, 'POST'); // idempotent for the same person
  await api(`/offers/${draft.id}/approvals/s2/approve`, manager.headers, undefined, 'POST', 400); // wrong role for HR step
  await api(`/offers/${draft.id}/approvals/s2/approve`, hr.headers, undefined, 'POST');
  await until('approval-needed notification for Finance (org admins)', async () => (await api('/notifications', finance)).data.find(item => item.title === 'Offer approval needed' && item.link.params.offerId === draft.id));
  await api(`/offers/${draft.id}/approvals/s3/approve`, finance, undefined, 'POST');
  const approved = await api(`/offers/${draft.id}`, recruiter);
  assert.equal(approved.status, 'APPROVED');
  assert(approved.approvalSteps.every(step => step.status === 'DONE'));
  await until('approved notification for the recruiter', async () => (await api('/notifications', recruiter)).data.find(item => item.title === `Offer approved: ${ada.name}`));
  pass('three-step approval (Finance because 28 is above the 25 midpoint): ordered, role-gated, one person per step, notified');

  await api(`/offers/${draft.id}/send`, manager.headers, undefined, 'POST', 403); // hiring managers approve but do not send
  const sent = await api(`/offers/${draft.id}/send`, recruiter, undefined, 'POST');
  assert.equal(sent.status, 'SENT');
  assert.equal((await api(`/offers/${draft.id}/send`, recruiter, undefined, 'POST')).status, 'SENT'); // idempotent
  const firstMail = await mailBody(ada.email, 'Your offer for', 1);
  assert.equal((await mails(ada.email, 'Your offer for')).length, 1, 'a repeated send must not email the candidate twice');
  const token = tokenFrom(firstMail);
  pass('offer sent once; the candidate email carries the signed link');

  // ---- Candidate responds -----------------------------------------------------------------------------------
  await pub('', 'not-a-token', undefined, 'GET', 400);
  const view = await pub('', token);
  assert.equal(view.status, 'VIEWED');
  assert.equal(view.canRespond, true);
  assert.equal(view.terms.base, 28);
  assert.match(view.letterText, new RegExp(`Dear ${ada.name},`));
  assert.match(view.letterText, /Confidentiality/);
  assert(!view.letterText.includes('{{'), 'merge fields must all be resolved');
  await until('viewed notification', async () => (await api('/notifications', recruiter)).data.find(item => item.title === `${ada.name} viewed the offer`));
  await pub('/negotiate', token, { message: '' }, 'POST', 400);
  await pub('/negotiate', token, { message: 'Could I start two weeks later?' }, 'POST');
  assert.equal((await api(`/offers/${draft.id}`, recruiter)).status, 'NEGOTIATING');
  await until('negotiation notification', async () => (await api('/notifications', recruiter)).data.find(item => item.title === `${ada.name} asked for changes`));
  const revised = await api(`/offers/${draft.id}`, recruiter, { terms: { joiningDate: day(45), joiningDateNote: 'candidate asked' } }, 'PATCH');
  assert.equal(revised.status, 'APPROVED', 'a non-compensation change needs only a resend');
  assert.equal(revised.version, 2);
  const whileRevising = await pub('', token);
  assert.equal(whileRevising.status, 'REVISING');
  assert.equal(whileRevising.canRespond, false);
  assert.equal(whileRevising.terms.joiningDate, day(30), 'the candidate still sees the terms that were sent');
  await pub('/accept', token, { fullName: ada.name }, 'POST', 409);
  await api(`/offers/${draft.id}/send`, recruiter, undefined, 'POST');
  await mailBody(ada.email, 'Your offer for', 2);
  assert.equal((await pub('', token)).terms.joiningDate, day(45));
  pass('view, negotiate, revise without re-approval, resend; the candidate never sees unsent terms');

  await pub('/accept', token, { fullName: 'Someone Else' }, 'POST', 400);
  const accepted = await pub('/accept', token, { fullName: ada.name.toLowerCase() }, 'POST');
  assert.equal(accepted.status, 'ACCEPTED');
  assert.equal((await pub('/accept', token, { fullName: ada.name }, 'POST')).status, 'ACCEPTED'); // idempotent
  await pub('/decline', token, {}, 'POST', 409);
  await mailBody(ada.email, 'Welcome to');
  await until('acceptance notification for HR', async () => (await api('/notifications', hr.headers)).data.find(item => item.title === `${ada.name} accepted the offer`));
  pass('acceptance needs the exact name, is idempotent, locks the offer, thanks the candidate and alerts HR');

  // ---- Hire saga ----------------------------------------------------------------------------------------------
  const employee = await until('employee created by the hire saga', async () => (await api('/employees', hr.headers)).data.find(item => item.name === ada.name), 90000);
  assert.match(employee.employeeCode, /^EMP-\d{4}-\d{5}$/);
  assert.equal(((await api('/employees', hr.headers)).data.filter(item => item.name === ada.name)).length, 1, 'a duplicate acceptance must not create a second employee');
  const record = await api(`/employees/${employee.id}`, hr.headers);
  assert.equal(record.status, 'PRE_BOARDING');
  assert.equal(record.designation, jobTitle, 'the designation defaults to the job title');
  assert.equal(await until('application marked HIRED', async () => { const stage = await stageOf(ada); return stage === 'HIRED' ? stage : null; }), 'HIRED');
  assert.equal(await until('other open application closed', async () => { const stage = await stageOf(alsoApplied); return stage === 'REJECTED' ? stage : null; }), 'REJECTED');
  const saga = await until('saga completed', async () => (await api('/hire-sagas?status=COMPLETED', hr.headers)).data.find(item => item.offerId === draft.id));
  assert.equal(saga.employeeNo, employee.employeeCode);
  await until('new-hire notification for HR', async () => (await api('/notifications', hr.headers)).data.find(item => item.type === 'NEW_HIRE' && item.title.includes(employee.employeeCode)));
  const tasks = (await api(`/employees/${employee.id}/onboarding`, hr.headers)).tasks;
  assert(tasks.length >= 10 && tasks.every(task => !task.done));
  assert.equal(employee.hrSyncStatus, 'PENDING');
  pass(`hire saga: ${employee.employeeCode}, application HIRED, other application closed, ${tasks.length} onboarding tasks, HRIS sync requested`);

  // ---- Onboarding ------------------------------------------------------------------------------------------------
  const newHire = await fixtureUser('employee', 'EMPLOYEE', ada.email);
  const mine = await api('/me/onboarding', newHire.headers);
  assert.equal(mine.employeeNo, employee.employeeCode);
  await api(`/me/onboarding/tasks/${mine.tasks[0].id}`, newHire.headers, { done: true }, 'PATCH', [200, 204]);
  const other = await fixtureUser('stranger', 'EMPLOYEE');
  await api('/me/onboarding', other.headers, undefined, 'GET', 404);
  await api(`/employees/${employee.id}/onboarding/tasks/${mine.tasks[1].id}`, other.headers, { done: true }, 'PATCH', [403, 404]);
  await api(`/employees/${employee.id}/onboarding/tasks/${mine.tasks[1].id}`, hr.headers, { done: true }, 'PATCH', [200, 204]);
  const progressed = (await api('/employees', hr.headers)).data.find(item => item.id === employee.id);
  assert(progressed.onboardingPct > 0);
  await api(`/employees/${employee.id}/activate`, other.headers, undefined, 'POST', 403);
  assert.equal((await api(`/employees/${employee.id}/activate`, hr.headers, undefined, 'POST')).status, 'ACTIVE');
  await api(`/employees/${employee.id}/activate`, hr.headers, undefined, 'POST', 409);
  pass('onboarding: the new hire and HR toggle tasks, others cannot, activation works once');

  // ---- Rescind -------------------------------------------------------------------------------------------------------
  const bo = await careerApply(job.id, `Rescind Phase${run}`, `phase5-bo-${run}@example.test`);
  await decideHire(recruiter, bo.id);
  const boOffer = await api(`/applications/${bo.id}/offer`, recruiter, { terms: { base: 22, joiningDate: day(30), expiryDate: day(7), reportsTo: 'Meera Nair' } }, 'POST', 201);
  await api(`/offers/${boOffer.id}/submit`, recruiter, undefined, 'POST');
  await approveAll(boOffer.id, people, false);
  await api(`/offers/${boOffer.id}/send`, recruiter, undefined, 'POST');
  const boToken = tokenFrom(await mailBody(bo.email, 'Your offer for'));
  await api(`/offers/${boOffer.id}/rescind`, recruiter, { reason: 'x' }, 'POST', 403);
  const rescinded = await api(`/offers/${boOffer.id}/rescind`, hr.headers, { reason: 'Role on hold' }, 'POST');
  assert.equal(rescinded.status, 'RESCINDED');
  const gone = await pub('', boToken);
  assert.equal(gone.status, 'RESCINDED');
  assert.equal(gone.canRespond, false);
  await pub('/accept', boToken, { fullName: bo.name }, 'POST', 409);
  const again = await api(`/applications/${bo.id}/offer`, recruiter, { terms: { base: 21, joiningDate: day(30), expiryDate: day(7), reportsTo: 'Meera Nair' } }, 'POST', 201);
  assert.equal(again.id, boOffer.id);
  assert.equal(again.status, 'DRAFT');
  assert(again.version > boOffer.version, 'a restarted offer gets a new version');
  pass('rescind is HR-only and shuts the candidate link; a rescinded offer can be started again as a new version');

  // ---- Withdrawal rescinds an unanswered offer ---------------------------------------------------------------
  const wd = await careerApply(job.id, `Withdraw Phase${run}`, `phase5-wd-${run}@example.test`);
  await decideHire(recruiter, wd.id);
  const wdOffer = await api(`/applications/${wd.id}/offer`, recruiter, { terms: { base: 22, joiningDate: day(30), expiryDate: day(7), reportsTo: 'Meera Nair' } }, 'POST', 201);
  await api(`/offers/${wdOffer.id}/submit`, recruiter, undefined, 'POST');
  await approveAll(wdOffer.id, people, false);
  await api(`/offers/${wdOffer.id}/send`, recruiter, undefined, 'POST');
  const withdrawn = await fetch(`${gateway}/api/v1/public/applications/${wd.id}/withdraw?tenantId=${tenant}`, { method: 'POST', headers: { 'X-Application-Receipt': wd.receipt } });
  assert.equal(withdrawn.status, 200, await withdrawn.text());
  await until('offer rescinded after the application was withdrawn', async () => (await api(`/offers/${wdOffer.id}`, recruiter)).status === 'RESCINDED');
  pass('withdrawing the application rescinds its open offer');

  // ---- Receipt status for the portal ---------------------------------------------------------------------------
  const receiptView = await api(`/public/applications/${ada.id}/offer?tenantId=${tenant}`, { 'X-Application-Receipt': ada.receipt });
  assert.equal(receiptView.status, 'ACCEPTED');
  assert.match(receiptView.offerUrl, /\/offer#token=/);
  await api(`/public/applications/${ada.id}/offer?tenantId=${tenant}`, { 'X-Application-Receipt': 'invalid' }, undefined, 'GET', 403);
  assert.equal((await api(`/public/applications/${early.id}/offer?tenantId=${tenant}`, { 'X-Application-Receipt': early.receipt })).status, 'NONE');
  pass('the portal status page can learn about the offer with the application receipt only');

  // ---- Referral reward ---------------------------------------------------------------------------------------------
  const referrer = await fixtureUser('referrer', 'EMPLOYEE');
  const consent = await api(`/public/consent?tenantId=${tenant}`, {});
  const form = new FormData();
  const referred = { name: `Referred Phase${run}`, email: `phase5-ref-${run}@example.test` };
  for (const [k, v] of Object.entries({ jobId: job.id, name: referred.name, email: referred.email, source: 'REFERRAL', processingConsent: 'true', consentVersion: consent.version })) form.set(k, v);
  form.set('resume', new Blob([resumePdf()], { type: 'application/pdf' }), 'referral.pdf');
  const referral = await api('/applications', { ...referrer.headers, 'Idempotency-Key': randomUUID() }, form, 'POST');
  await until('referral assessed', async () => (await fetch(`${gateway}/api/v1/applications/${referral.id}/assessment`, { headers: recruiter })).status === 200, 180000);
  await decideHire(recruiter, referral.id);
  const refOffer = await api(`/applications/${referral.id}/offer`, recruiter, { terms: { base: 22, joiningDate: day(30), expiryDate: day(7), reportsTo: 'Meera Nair' } }, 'POST', 201);
  await api(`/offers/${refOffer.id}/submit`, recruiter, undefined, 'POST');
  await approveAll(refOffer.id, people, false);
  await api(`/offers/${refOffer.id}/send`, recruiter, undefined, 'POST');
  const refToken = tokenFrom(await mailBody(referred.email, 'Your offer for'));
  const restart = process.env.OPENHIRE_TEST_RESTART === 'true';
  // With the service down, the acceptance event must wait in Kafka and the saga must run once it is back.
  if (restart) execFileSync('docker', ['stop', 'openhire-employee-service-1'], { cwd: backend, stdio: 'ignore' });
  try {
    await pub('/accept', refToken, { fullName: referred.name }, 'POST');
    if (restart) {
      await sleep(8000);
      assert.equal((await api('/employees', hr.headers, undefined, 'GET', [200, 500, 502, 503])).data?.find(item => item.name === referred.name), undefined, 'nobody can have created the employee while the service is down');
    }
  } finally {
    if (restart) execFileSync('docker', ['start', 'openhire-employee-service-1'], { cwd: backend, stdio: 'ignore' });
  }
  const refEmployee = await until('referred employee created', async () => (await api('/employees', hr.headers)).data.find(item => item.name === referred.name), 180000);
  assert.equal((await api('/employees', hr.headers)).data.filter(item => item.name === referred.name).length, 1);
  assert.notEqual(refEmployee.employeeCode, employee.employeeCode);
  const reward = await until('referral reward for the referrer', async () => (await api('/me/referral-rewards', referrer.headers)).data.find(item => item.applicationId === referral.id));
  assert.equal(reward.status, 'PENDING_PROBATION');
  assert.equal((await api('/me/referral-rewards', other.headers)).data.length, 0, 'rewards are private to the referrer');
  await until('saga completed after restart', async () => (await api('/hire-sagas?status=COMPLETED', hr.headers)).data.find(item => item.offerId === refOffer.id), 180000);
  pass(`referral reward created PENDING_PROBATION for the referrer${restart ? '; the acceptance happened while employee-service was down and the saga still completed once with a unique number' : ''}`);

  console.log(`Phase 5 workflow completed. Development jobs ${job.id}, ${otherJob.id} and employees ${employee.employeeCode}, ${refEmployee.employeeCode} are retained as test data.`);
} finally {
  const admin = createdUsers.length ? await adminHeaders() : {};
  for (const id of createdUsers) {
    const res = await fetch(`${keycloak}/admin/realms/openhire-staff/users/${id}`, { method: 'DELETE', headers: admin });
    if (res.status !== 204) console.error(`Fixture user cleanup requires review: ${id}`);
  }
}
