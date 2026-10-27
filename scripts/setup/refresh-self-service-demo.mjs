import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { resumePdf } from '../testing/phase3-pdf-fixture.mjs';

/** Existing-tenant self-service data only. No fabricated hires, scores, workflow events, credentials or tenant migrations. */
export async function refreshSelfServiceDemo({ api, people, asEmployee, asRecruiter, manifest, checkpoint, tenantId, slug, tenantName }) {
  assert(manifest.tenantId === tenantId && manifest.tag === `${slug}-demo-v1`, 'Seed manifest tenant mismatch');
  assert(people.EMPLOYEE?.id && people.EMPLOYEE.username.endsWith(`@${slug}.example.test`), 'Existing demo employee required');
  const employeePage = await api('/me/referrals?limit=1', undefined, 'GET', asEmployee);
  assert(Array.isArray(employeePage.data) && typeof employeePage.hasMore === 'boolean', 'Rebuild application-service for paginated employee submissions');
  const internalJobs = await api('/internal-jobs', undefined, 'GET', asEmployee);
  assert(Array.isArray(internalJobs.data) && internalJobs.data.length > 0, 'Employee internal jobs unavailable');
  const onboarding = await api('/me/onboarding', undefined, 'GET', asEmployee);
  assert(onboarding.employeeNo && Array.isArray(onboarding.tasks), 'Existing demo employee onboarding must be ready; no fake employee was inserted');
  const jobs = [];
  for (const label of ['Backend Engineer', 'Data Engineer']) {
    const saved = manifest.jobs.find(j => j.label === label); assert(saved, 'Original demo job missing');
    const job = await api(`/jobs/${saved.id}`, undefined, 'GET', asRecruiter);
    assert(job.status === 'OPEN' && job.title === `[DEMO:${slug.toUpperCase()}] ${label}`, 'Demo job changed; refusing to overwrite it'); jobs.push(job);
  }
  const policy = await api(`/public/consent?tenantId=${tenantId}`, undefined, 'GET', {});
  assert(policy.version, 'Current processing consent required');
  manifest.selfServiceRefresh ??= { tag: `${slug}-self-service-v1`, candidates: [], employeeSubmissions: [], messages: [] };
  const state = manifest.selfServiceRefresh;
  function form(name, email, jobId, source, key, token) {
    const data = new FormData();
    for (const [field, value] of Object.entries({ tenantId, jobId, name, email, phone: '+1 202 555 0199', source,
      externalReference: `${state.tag}:${key}`, processingConsent: 'true', consentVersion: policy.version,
      candidateReason: 'Synthetic local self-service demo only. Not a real applicant or hiring commitment.',
      answers: '{}', ...(token ? { receiptToken: token } : {}) })) data.set(field, value);
    data.set('resume', new Blob([resumePdf([`${name} - SYNTHETIC LOCAL TEST DATA`, 'Backend Engineer - 5 years of synthetic experience.',
      'Skills: Java, Spring Boot, Kafka, SQL, MongoDB. Built APIs and automated tests.', 'Education: BSc Computer Science. Not a real person or employment history.'])], { type: 'application/pdf' }), 'self-service-demo-resume.pdf');
    return data;
  }
  for (let index = 0; index < 2; index++) {
    const key = `candidate-${index + 1}`;
    let item = state.candidates.find(c => c.key === key);
    if (!item) {
      item = { key, name: `${tenantName} Demo Portal Candidate ${index + 1}`, email: `demo.portal-${index + 1}@${slug}.example.test`, token: randomBytes(48).toString('base64url'), jobId: jobs[index].id };
      state.candidates.push(item); checkpoint(); // Save the receipt before a retryable intake request.
    }
    if (!item.id) {
      const app = await api('/public/applications', form(item.name, item.email, item.jobId, 'CAREER', key, item.token), 'POST', {});
      assert(app.id, 'Candidate intake failed'); item.id = app.id; checkpoint();
      console.log(`PASS: self-service ${key} saved`);
    }
    const headers = { 'X-Application-Receipt': item.token };
    const status = await api(`/public/applications/${item.id}?tenantId=${tenantId}`, undefined, 'GET', {}, headers);
    assert(status.email === item.email && status.name === item.name, 'Candidate receipt/profile mismatch');
    if (!state.messages.includes(key)) {
      const conversation = await api('/conversations', { applicationId: item.id }, 'POST', asRecruiter);
      await api(`/conversations/${conversation.id}/messages`, { text: `Synthetic demo: thank you for applying to ${tenantName}. Your application is with our hiring team. You can reply here with questions. This is a local test, not a real hiring process.`, channel: 'EMAIL' }, 'POST', asRecruiter);
      state.messages.push(key); checkpoint();
    }
  }
  for (let index = 0; index < 3; index++) {
    const key = index === 2 ? 'internal' : `referral-${index + 1}`;
    if (state.employeeSubmissions.some(s => s.key === key)) continue;
    const source = index === 2 ? 'INTERNAL' : 'REFERRAL';
    const name = index === 2 ? `${tenantName} Demo Employee` : `${tenantName} Demo Referred Candidate ${index + 1}`;
    const email = index === 2 ? people.EMPLOYEE.username : `demo.referred-${index + 1}@${slug}.example.test`;
    const app = await api('/applications', form(name, email, jobs[index % 2].id, source, key), 'POST', asEmployee);
    assert(app.id, 'Employee submission failed'); state.employeeSubmissions.push({ key, id: app.id }); checkpoint();
    console.log(`PASS: employee ${key} saved`);
  }
  const seen = []; let after;
  do {
    const page = await api(`/me/referrals?limit=2${after ? `&after=${encodeURIComponent(after)}` : ''}`, undefined, 'GET', asEmployee);
    seen.push(...page.data); after = page.hasMore ? page.nextCursor : null;
    assert(!page.hasMore || after, 'Missing employee page cursor'); assert(seen.length <= 10000, 'Unexpected paging loop');
    assert(page.data.every(s => !('score' in s) && !('assessment' in s)), 'Internal scores exposed');
  } while (after);
  assert(state.employeeSubmissions.every(s => seen.some(row => row.id === s.id)), 'Employee submissions missing from their own list');
  return { candidateApplications: state.candidates.length, employeeSubmissions: state.employeeSubmissions.length, employeeOnboardingTasks: onboarding.tasks.length, messages: state.messages.length, paginationVerified: true };
}
