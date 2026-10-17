// Local-only Phase 4 acceptance: interviews, blind scorecards, decision, messaging, notifications and delivery.
// Creates labelled development records (job, application, messages) and two temporary interviewer users,
// which it removes at the end. Set OPENHIRE_TEST_OUTAGE=true to also stop and restart Mailpit to prove retries.
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
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const pass = text => console.log(`PASS: ${text}`);

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

async function mailTo(address, subjectPart, timeoutMs = 60000) {
  return until(`email "${subjectPart}" to ${address}`, async () => {
    const res = await fetch(`${mailpit}/api/v1/search?query=${encodeURIComponent(`to:"${address}"`)}`);
    const found = (await res.json()).messages ?? [];
    return found.find(message => message.Subject.includes(subjectPart));
  }, timeoutMs);
}

// ---- Fixtures: two interviewers (Keycloak) -----------------------------------------------------------------

// Keycloak admin tokens last about a minute, so every admin call fetches a fresh one.
async function adminHeaders() {
  const response = await fetch(`${keycloak}/realms/master/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({ grant_type: 'password', client_id: 'admin-cli', username: process.env.KEYCLOAK_ADMIN_USERNAME || 'admin', password: process.env.KEYCLOAK_ADMIN_PASSWORD || 'admin' }),
  });
  assert.equal(response.status, 200, 'Local fixture provisioning requires Keycloak administration');
  return { Authorization: `Bearer ${(await response.json()).access_token}`, 'Content-Type': 'application/json' };
}
const createdUsers = [];

async function interviewer(label, roleName = 'INTERVIEWER') {
  const username = `phase4-${label}-${run}@example.test`;
  const password = `${randomUUID()}aA1!`;
  const admin = await adminHeaders();
  const res = await fetch(`${keycloak}/admin/realms/openhire-staff/users`, {
    method: 'POST', headers: admin,
    body: JSON.stringify({ username, email: username, firstName: `Interviewer ${label.toUpperCase()}`, lastName: run, enabled: true, emailVerified: true, attributes: { organization_id: [tenant] }, credentials: [{ type: 'password', value: password, temporary: false }] }),
  });
  assert.equal(res.status, 201);
  const id = res.headers.get('location').split('/').pop();
  createdUsers.push(id);
  const role = await (await fetch(`${keycloak}/admin/realms/openhire-staff/roles/${roleName}`, { headers: admin })).json();
  assert.equal((await fetch(`${keycloak}/admin/realms/openhire-staff/users/${id}/role-mappings/realm`, { method: 'POST', headers: admin, body: JSON.stringify([role]) })).status, 204);
  return { email: username, name: `Interviewer ${label.toUpperCase()} ${run}`, headers: await login(username, password) };
}

try {
  const recruiterSeed = seed.users.find(u => u.username === 'recruiter@openhire.local');
  const recruiter = await login(recruiterSeed.username, recruiterSeed.credentials[0].value);
  const asha = await interviewer('a');
  const ben = await interviewer('b');
  const manager = await interviewer('m', 'HIRING_MANAGER');
  pass('recruiter, hiring manager and two interviewer sessions');

  // ---- Job with a two-round plan ---------------------------------------------------------------------------
  const title = `Phase 4 verification engineer ${run}`;
  const job = await api('/jobs', { ...recruiter, 'Idempotency-Key': randomUUID() }, {
    title, team: 'Platform', level: 'L4', location: 'Remote', salaryBand: { currency: 'INR', min: 20, max: 30 },
    mustHaveSkills: ['Java', 'Spring Boot'], knockoutQuestions: [],
    rubric: [{ id: 'c1', label: 'Problem solving', weight: 60 }, { id: 'c2', label: 'Communication', weight: 40 }],
    description: 'Phase 4 verification job', status: 'DRAFT',
  }, 'POST', [200, 201]);
  const planned = await api(`/jobs/${job.id}/versions/${job.currentVersion.version}/interview-plan`, recruiter, {
    interviewPlan: [
      { id: 'p1', name: 'Technical', durationMin: 60, interviewerNames: [] },
      { id: 'p2', name: 'System design', durationMin: 60, interviewerNames: [] },
    ],
  }, 'PUT');
  assert.equal(planned.currentVersion.interviewPlan.length, 2);
  await api(`/jobs/${job.id}/submit`, recruiter, undefined, 'POST');
  const approvals = await api('/approvals?scope=waiting_on_me', recruiter);
  const approval = approvals.data.find(item => item.title === title);
  // The job's approval step is unassigned, so everyone with the hiring-manager role hears about it.
  const needed = await until('approval-needed notification', async () => (await api('/notifications', manager.headers)).data.find(item => item.type === 'APPROVAL_REQUESTED' && item.body === title));
  assert.equal(needed.link.route, 'approvals');
  assert.equal(needed.link.params.id, approval.id);
  await api(`/approvals/${approval.id}/approve`, recruiter, undefined, 'POST', [200, 204]);
  await until('approval outcome for the requester', async () => (await api('/notifications', recruiter)).data.find(item => item.type === 'APPROVAL_DECIDED' && item.title === `Approved: ${title}`));
  pass('approvers notified of a waiting approval; the requester notified of the outcome');
  await api(`/jobs/${job.id}/publish`, recruiter, undefined, 'POST');
  pass(`job ${job.id} published with a two-round interview plan`);

  // ---- Career application with a receipt ------------------------------------------------------------------
  const consent = await api(`/public/consent?tenantId=${tenant}`, {});
  const candidateEmail = `phase4-candidate-${run}@example.test`;
  const receipt = randomUUID() + randomUUID();
  const form = new FormData();
  for (const [k, v] of Object.entries({ tenantId: tenant, jobId: job.id, name: `Priya Phase${run}`, email: candidateEmail, phone: '+919800000001', processingConsent: 'true', consentVersion: consent.version, receiptToken: receipt, answers: '{}' })) form.set(k, v);
  form.set('resume', new Blob([resumePdf()], { type: 'application/pdf' }), 'phase4-fixture.pdf');
  const application = await api('/public/applications', { 'Idempotency-Key': randomUUID() }, form, 'POST');
  await mailTo(candidateEmail, 'We received your application');
  pass('career application acknowledged by email through the dispatcher');

  await until('screening', async () => (await api(`/public/applications/${application.id}?tenantId=${tenant}`, { 'X-Application-Receipt': receipt })).stage === 'AI_ASSESSED', 180000);
  await api(`/applications/${application.id}/shortlist`, recruiter, undefined, 'POST');
  await api(`/applications/${application.id}/stage`, recruiter, { stage: 'INTERVIEWING' }, 'PATCH');
  const loop = await until('interview loop from the INTERVIEWING event', () => api(`/applications/${application.id}/interview-loop`, recruiter, undefined, 'GET', [200, 404]).then(value => value?.rounds ? value : null));
  assert.deepEqual(loop.rounds.map(round => round.name), ['Technical', 'System design']);
  const [technical, design] = loop.rounds;
  pass('moving to Interviewing planned the loop from the job interview plan');

  // ---- Candidate self-scheduling with held slots ------------------------------------------------------------
  await api(`/rounds/${design.id}/schedule`, recruiter, { start: new Date(Date.now() - 30 * 86400000 - 1000).toISOString(), panel: [{ name: ben.name, email: ben.email }] }, 'POST', 400);
  await api(`/rounds/${design.id}/panel`, recruiter, { panel: [{ name: ben.name, email: ben.email }] }, 'PUT');
  const link = await api(`/rounds/${design.id}/scheduling-link`, recruiter, undefined, 'POST');
  const token = decodeURIComponent(new URL(link.url).hash.replace('#token=', ''));
  assert(link.slots.length > 0, 'No slots offered');
  await mailTo(candidateEmail, 'Choose a time for your System design interview');
  const offer = await fetch(`${gateway}/api/v1/public/scheduling`, { headers: { 'X-Scheduling-Token': token } }).then(r => r.json());
  assert.deepEqual(offer.slots, link.slots);
  const forged = await fetch(`${gateway}/api/v1/public/scheduling`, { headers: { 'X-Scheduling-Token': token.slice(0, -3) + 'AAA' } });
  assert.equal(forged.status, 400);
  const notOffered = await fetch(`${gateway}/api/v1/public/scheduling/book`, { method: 'POST', headers: { 'X-Scheduling-Token': token, 'Content-Type': 'application/json' }, body: JSON.stringify({ start: '2030-01-01T05:00:00Z' }) });
  assert.equal(notOffered.status, 400);
  const booked = await fetch(`${gateway}/api/v1/public/scheduling/book`, { method: 'POST', headers: { 'X-Scheduling-Token': token, 'Content-Type': 'application/json' }, body: JSON.stringify({ start: offer.slots.at(-1) }) }).then(async r => { assert.equal(r.status, 200, await r.clone().text()); return r.json(); });
  assert.equal(booked.bookedStart, offer.slots.at(-1));
  await mailTo(candidateEmail, 'Your System design interview');
  pass('candidate booked a held slot via the signed link; forged tokens and unoffered times are rejected');

  // ---- Double-booking protection ---------------------------------------------------------------------------
  const clash = await fetch(`${gateway}/api/v1/rounds/${technical.id}/schedule`, { method: 'POST', headers: { ...recruiter, 'Content-Type': 'application/json' }, body: JSON.stringify({ start: booked.bookedStart, panel: [{ name: ben.name, email: ben.email }] }) });
  assert.equal(clash.status, 409, 'The same interviewer was double-booked');
  pass('the same interviewer cannot be double-booked');

  // ---- Candidate moves, then cancels, their booking --------------------------------------------------------
  const scheduling = (path, body) => fetch(`${gateway}/api/v1/public/scheduling${path}`, { method: 'POST', headers: { 'X-Scheduling-Token': token, 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) })
    .then(async r => { assert.equal(r.status, 200, await r.clone().text()); return r.json(); });
  if (booked.canChange) {
    const alternatives = await scheduling('/alternatives');
    assert.equal(alternatives.bookedStart, booked.bookedStart, 'The booking must stand until a new time is chosen');
    assert(alternatives.slots.length > 0 && !alternatives.slots.includes(booked.bookedStart));
    const moved = await scheduling('/book', { start: alternatives.slots.at(-1) });
    assert.equal(moved.bookedStart, alternatives.slots.at(-1));
    await mailTo(candidateEmail, 'New time for your System design');
    const cancelled = await scheduling('/cancel', { reason: 'Accepted another offer' });
    assert.equal(cancelled.status, 'CANCELLED_BY_CANDIDATE');
    const reopened = await fetch(`${gateway}/api/v1/public/scheduling`, { headers: { 'X-Scheduling-Token': token } }).then(r => r.json());
    assert.equal(reopened.status, 'CANCELLED_BY_CANDIDATE');
    assert.equal(reopened.slots.length, 0, 'A cancelled link must not offer new times');
    await until('candidate cancellation alert', async () => (await api('/notifications', ben.headers)).data.find(item => item.title.includes('cancelled System design') && item.body.includes('Accepted another offer')));
    pass('candidate moved the booking (it stood until the new time was chosen), then cancelled; the panel was told why');
  } else {
    assert.match(booked.changeNote ?? '', /24 hours/);
    pass(`candidate changes correctly refused inside the 24-hour window (${booked.bookedStart}); move/cancel not exercised`);
  }

  // ---- Notifications and preferences -----------------------------------------------------------------------
  const benNotes = await until('Ben\'s assignment notification', async () => {
    const list = await api('/notifications', ben.headers);
    return list.data.find(item => item.type === 'ASSIGNED');
  });
  assert.equal(benNotes.link.route, 'myInterviews');
  await mailTo(ben.email, "You're interviewing");
  const prefs = await api('/me/notification-preferences', asha.headers);
  prefs.matrix.ASSIGNED_TO_JOB_ROUND.IN_APP = false;
  prefs.matrix.ASSIGNED_TO_JOB_ROUND.EMAIL = false;
  const saved = await api('/me/notification-preferences', asha.headers, prefs, 'PUT');
  assert.equal(saved.matrix.ASSIGNED_TO_JOB_ROUND.IN_APP, false);
  pass('interviewer notified in the app and by email; preferences round-trip');

  // ---- Blind scorecards, debrief and the human decision ------------------------------------------------------
  const pastStart = new Date(Date.now() - 3 * 3600000);
  pastStart.setUTCMinutes(0, 0, 0);
  await api(`/rounds/${technical.id}/schedule`, recruiter, { start: pastStart.toISOString(), panel: [{ name: asha.name, email: asha.email }, { name: ben.name, email: ben.email }] }, 'POST');
  // Ben's second assignment proves the event was processed before checking that Asha's opt-out held.
  await until('the Technical assignment to reach Ben', async () => (await api('/notifications', ben.headers)).data.filter(item => item.type === 'ASSIGNED').length >= 2);
  assert.equal((await api('/notifications', asha.headers)).data.filter(item => item.type === 'ASSIGNED').length, 0, 'Asha opted out of assignment notifications');
  const mine = await api('/me/interviews', asha.headers);
  assert(mine.data.some(item => item.roundId === technical.id && item.myScorecard === 'DUE'));
  const ratings = (good) => [{ competencyId: 'c1', rating: good ? 'EXCELLENT' : 'MIXED' }, { competencyId: 'c2', rating: 'GOOD' }];
  await api(`/rounds/${technical.id}/scorecards`, recruiter, { ratings: ratings(true), evidence: 'Not on the panel' }, 'POST', 409);
  await api(`/rounds/${technical.id}/scorecards`, asha.headers, { ratings: ratings(true), evidence: 'Clear reasoning about consistency.' }, 'POST');
  assert.equal((await api(`/rounds/${technical.id}/scorecards`, ben.headers)).data.length, 0, 'Ben saw feedback before submitting his own');
  await api(`/applications/${application.id}/debrief`, ben.headers, undefined, 'GET', 403);
  await api(`/rounds/${technical.id}/scorecards`, ben.headers, { ratings: ratings(false), evidence: 'Struggled with partitioning trade-offs.' }, 'POST');
  assert.equal((await api(`/rounds/${technical.id}/scorecards`, ben.headers)).data.length, 2);
  const debrief = await api(`/applications/${application.id}/debrief`, recruiter);
  assert.equal(debrief.generatedBy, 'RULES');
  assert.equal(debrief.roundsCompleted, 1);
  assert(debrief.disagreements.some(point => point.text.includes('Problem solving')));
  assert(debrief.consistentStrengths.some(point => point.text.startsWith('Communication')));
  await api(`/applications/${application.id}/decision`, recruiter, { decision: 'HIRE', reason: '' }, 'POST', 400);
  const decision = await api(`/applications/${application.id}/decision`, recruiter, { decision: 'HIRE', reason: 'Strong technical signal; concerns are coachable.' }, 'POST');
  assert.equal(decision.decision, 'HIRE');
  const status = await api(`/public/applications/${application.id}?tenantId=${tenant}`, { 'X-Application-Receipt': receipt });
  assert.equal(status.stage, 'OFFER');
  pass('blind scorecards, attributed debrief and a recorded hire decision (application moved to OFFER)');

  // ---- Conversation, delivery status and candidate reply ----------------------------------------------------
  const conversation = await api('/conversations', recruiter, { applicationId: application.id }, 'POST', 201);
  const sent = await api(`/conversations/${conversation.id}/messages`, recruiter, { text: `Hello from the hiring team ${run}`, channel: 'EMAIL' }, 'POST', 201);
  await mailTo(candidateEmail, 'Message from the');
  await until('message SENT status', async () => (await api(`/conversations/${conversation.id}`, recruiter)).messages.find(item => item.id === sent.id)?.status === 'SENT');
  const whatsapp = await api(`/conversations/${conversation.id}/messages`, recruiter, { text: `WhatsApp attempt ${run}`, channel: 'WHATSAPP' }, 'POST', 201);
  const fallback = await until('WhatsApp fallback delivery', async () => {
    const delivery = await api(`/deliveries/${whatsapp.id}`, recruiter, undefined, 'GET', [200, 404]);
    return delivery?.status === 'SENT' ? delivery : null;
  });
  assert.equal(fallback.channelUsed, 'EMAIL');
  assert.match(fallback.reason, /WHATSAPP is not configured/);
  const thread = await api(`/public/applications/${application.id}/messages?tenantId=${tenant}`, { 'X-Application-Receipt': receipt });
  assert(thread.messages.some(item => item.text === `Hello from the hiring team ${run}`));
  await api(`/public/applications/${application.id}/messages?tenantId=${tenant}`, { 'X-Application-Receipt': 'invalid' }, undefined, 'GET', 403);
  await api(`/public/applications/${application.id}/messages?tenantId=${tenant}`, { 'X-Application-Receipt': receipt }, { text: 'Thanks, Tuesday works.' }, 'POST', 201);
  await until('reply notification', async () => (await api('/notifications?filter=unread', recruiter)).data.find(item => item.type === 'CANDIDATE_REPLIED' && item.title.includes(run)));
  const conversations = await api('/conversations', recruiter);
  assert(conversations.data.find(item => item.id === conversation.id).unread);
  pass('two-way messaging with delivery status, WhatsApp-to-email fallback, receipt-protected candidate replies');

  // ---- Templates --------------------------------------------------------------------------------------------
  const templates = await api('/message-templates', recruiter);
  assert(templates.data.some(item => item.key === 'interview-invitation'));
  await api('/message-templates/not-moving-forward', recruiter, { channels: ['EMAIL'], subject: 'Hi', body: 'Hello {{candidate.salary}}' }, 'PUT', 400);
  pass('message templates listed; unknown variables are rejected');

  // ---- Optional: provider outage, retry topics and recovery --------------------------------------------------
  if (process.env.OPENHIRE_TEST_OUTAGE === 'true') {
    execFileSync('docker', ['compose', 'stop', 'mailpit'], { cwd: new URL('../..', import.meta.url).pathname, stdio: 'ignore' });
    try {
      const outage = await api(`/conversations/${conversation.id}/messages`, recruiter, { text: `During outage ${run}`, channel: 'EMAIL' }, 'POST', 201);
      await until('retry attempts during the outage', async () => {
        const delivery = await api(`/deliveries/${outage.id}`, recruiter, undefined, 'GET', [200, 404]);
        return delivery?.attempts >= 2 && delivery.status !== 'SENT' ? delivery : null;
      }, 60000);
      execFileSync('docker', ['compose', 'start', 'mailpit'], { cwd: new URL('../..', import.meta.url).pathname, stdio: 'ignore' });
      await until('delivery after recovery', async () => (await api(`/deliveries/${outage.id}`, recruiter)).status === 'SENT', 240000);
      pass('SMTP outage retried through the retry topics and delivered after recovery');
    } finally {
      execFileSync('docker', ['compose', 'start', 'mailpit'], { cwd: new URL('../..', import.meta.url).pathname, stdio: 'ignore' });
    }
  }
  console.log(`Phase 4 workflow completed. Development job ${job.id} and application ${application.id} are retained as test data.`);
} finally {
  const admin = createdUsers.length ? await adminHeaders() : {};
  for (const id of createdUsers) {
    const res = await fetch(`${keycloak}/admin/realms/openhire-staff/users/${id}`, { method: 'DELETE', headers: admin });
    if (res.status !== 204) console.error(`Fixture user cleanup requires review: ${id}`);
  }
}
