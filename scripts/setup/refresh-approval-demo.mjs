import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { resumePdf } from '../testing/phase3-pdf-fixture.mjs';
import { demoJobCategory } from './job-category-default.mjs';

/** Additive, checkpointed examples for the v2 approval contracts. Never patch untagged records. */
export async function refreshApprovalDemo({ api, people, asRecruiter, asManager, asHr, asOrgAdmin, manifest, checkpoint, tenantId, slug, tenantName, originalRecruiter }) {
  const prefix = `[DEMO:${slug.toUpperCase()}][APPROVALS-V2]`;
  // Fail before any domain writes if the services have not been rebuilt.
  assert(Array.isArray((await api('/offers/approval-inbox?scope=requested_by_me')).data), 'Offer inbox contract unavailable');
  const originalJob = await api(`/jobs/${manifest.jobs[0].id}`);
  assert('headcount' in originalJob.currentVersion, 'Rebuild job-service before refreshing demo data');
  const requests = async () => (await api('/approvals?scope=requested_by_me')).data;
  const priorRequest = (await requests())[0];
  assert(priorRequest && 'headcountException' in await api(`/approvals/${priorRequest.id}`), 'Rebuild approval-service before refreshing demo data');
  manifest.approvalRefresh ??= { tag: `${slug}-approvals-v2`, jobs: {}, applications: {}, offers: {}, requests: {}, completed: [] };
  const state = manifest.approvalRefresh;
  const done = key => { if (!state.completed.includes(key)) state.completed.push(key); checkpoint(); console.log(`PASS: approval refresh ${key}`); };
  const body = (title, description = '5 to 8 years building backend services in production. Synthetic local demo vacancy only.') => ({
    title, team: 'Demo Platform', level: 'L4', location: 'Pune, onsite — synthetic demo', hiringManagerId: people.HIRING_MANAGER.id,
    salaryBand: { currency: 'INR', min: 3800000, max: 4800000 }, headcount: 1, positionType: 'NEW_POSITION',
    hiringReason: 'Synthetic approval scenario only; no real job advertisement or headcount commitment.',
    qualification: 'Relevant degree or equivalent practical experience (synthetic demo)', goodToHaveSkills: ['Microsoft Azure'],
    mustHaveSkills: ['Java', 'Spring Boot', 'Kafka'], knockoutQuestions: [],
    rubric: [{ id: 'payments', label: 'Payments domain', weight: 20 }, { id: 'ownership', label: 'Ownership', weight: 15 }, { id: 'backend', label: 'Backend engineering', weight: 65 }], description, status: 'DRAFT',
  });
  async function job(key, label) {
    if (state.jobs[key]) return api(`/jobs/${state.jobs[key]}`);
    const title = `${prefix} ${label}`;
    const matches = (await api('/jobs')).data.filter(j => j.title === title);
    assert(matches.length <= 1, 'Ambiguous approval demo job; refusing to duplicate or overwrite');
    const value = matches[0] ? await api(`/jobs/${matches[0].id}`) : await api('/jobs', { ...body(title), categoryId: await demoJobCategory(api, asOrgAdmin), workMode: 'ONSITE' }, 'POST');
    assert(value.activity.some(a => a.actor === people.RECRUITER.id && a.text === 'Created the job'), 'Demo job ownership differs; not changed');
    state.jobs[key] = value.id; checkpoint(); return value;
  }
  async function requestFor(title, type) {
    const found = (await requests()).filter(r => r.title === title && r.type === type && r.requestedBy === people.RECRUITER.id);
    assert(found.length <= 1, 'Ambiguous demo approval request');
    return found[0];
  }

  let sre = await job('newJob', 'Site Reliability Engineer');
  if (!state.completed.includes('new-job')) {
    if (sre.status === 'DRAFT') await api(`/jobs/${sre.id}/submit`, undefined, 'POST');
    const request = await requestFor(sre.title, 'JOB_NEW');
    assert(request, 'New job approval was not created');
    const detail = await api(`/approvals/${request.id}`);
    assert(detail.terms.some(t => t.label === 'Headcount' && t.value === '1, new position'), 'New job metadata was not saved');
    state.requests.newJob = request.id; done('new-job');
  }
  if (!state.completed.includes('headcount')) {
    const title = `${sre.title}, +1 seat`;
    let request = await requestFor(title, 'HEADCOUNT_EXCEPTION');
    if (!request) request = await api('/approvals', { type: 'HEADCOUNT_EXCEPTION', subjectId: sre.id, title: sre.title,
      steps: [{ id: 'demo-manager', approver: people.HIRING_MANAGER.id, role: 'HIRING_MANAGER', group: 0 },
        { id: 'demo-hr', approver: people.HR_ADMIN.id, role: 'HR_ADMIN', group: 1 }],
      headcountException: { jobTitle: sre.title, currentHeadcount: 1, requestedHeadcount: 2, reason: 'Synthetic demo: two strong finalists and an on-call rotation short by one. No real headcount change.' } }, 'POST');
    state.requests.headcount = request.id; checkpoint();
    let detail = await api(`/approvals/${request.id}`);
    if (detail.steps.find(s => s.status === 'CURRENT')?.id === 'demo-manager') {
      await api(`/approvals/${request.id}/approve`, undefined, 'POST', asManager);
      detail = await api(`/approvals/${request.id}`);
    }
    assert(detail.headcountException?.requestedHeadcount === 2 && detail.steps[1].status === 'CURRENT', 'Headcount scenario was changed; not overwritten');
    done('headcount');
  }

  let changed = await job('versionChange', 'Senior Java Backend Engineer');
  if (!state.completed.includes('version-change')) {
    if (changed.status === 'DRAFT' && changed.currentVersion.version === 1) {
      const prior = await requestFor(changed.title, 'JOB_NEW');
      if (!prior) await api(`/jobs/${changed.id}/submit`, undefined, 'POST');
      changed = await api(`/jobs/${changed.id}`);
    }
    if (changed.status === 'PENDING_APPROVAL' && changed.currentVersion.version === 1) {
      const prior = await requestFor(changed.title, 'JOB_NEW');
      assert(prior, 'Baseline approval missing');
      await api(`/approvals/${prior.id}/request-changes`, { reason: 'Synthetic demo review: widen experience and rebalance payments versus ownership.' }, 'POST', asManager);
      changed = await api(`/jobs/${changed.id}`);
    }
    if (changed.status === 'DRAFT' && changed.currentVersion.version === 1) {
      const input = body(changed.title, '4 to 8 years building backend services in production. Synthetic local demo vacancy only.');
      input.rubric[0].weight = 15; input.rubric[1].weight = 20;
      changed = await api(`/jobs/${changed.id}`, input, 'PATCH', asRecruiter, { 'If-Match': '1' });
    }
    assert(changed.currentVersion.version === 2, 'Demo version history differs; not overwritten');
    if (changed.status === 'DRAFT') await api(`/jobs/${changed.id}/submit`, undefined, 'POST');
    const request = await requestFor(`${changed.title}, version 2`, 'JOB_VERSION_CHANGE');
    assert(request, 'Expected a real JOB_VERSION_CHANGE request');
    const detail = await api(`/approvals/${request.id}`);
    assert(detail.comparison?.previousVersion === 1 && detail.comparison?.proposedVersion === 2 && detail.diff.length === 3, 'Version comparison was not saved');
    state.requests.versionChange = request.id; done('version-change');
  }

  let offerJob = await job('offerReview', 'Offer review role');
  if (!state.completed.includes('offer-job')) {
    if (offerJob.status === 'DRAFT') await api(`/jobs/${offerJob.id}/submit`, undefined, 'POST');
    if (offerJob.status !== 'OPEN') {
      const request = await requestFor(offerJob.title, 'JOB_NEW');
      assert(request, 'Offer job approval missing');
      if (request.status === 'PENDING') await api(`/approvals/${request.id}/approve`, undefined, 'POST', asManager);
      await api(`/jobs/${offerJob.id}/publish`, undefined, 'POST');
    }
    done('offer-job');
  }
  const rows = async () => (await api(`/applications?jobId=${offerJob.id}&size=100`)).data;
  const consent = await api(`/public/consent?tenantId=${tenantId}`);
  for (const key of ['finance', 'approved']) {
    if (!state.applications[key]) {
      const name = `${tenantName} Demo Approval Candidate ${key === 'finance' ? 'Finance' : 'Approved'}`;
      const matches = (await rows()).filter(r => r.candidateName === name && r.jobId === offerJob.id);
      assert(matches.length <= 1, 'Ambiguous synthetic application');
      if (matches[0]) state.applications[key] = matches[0].applicationId;
      else {
        const form = new FormData();
        for (const [k, v] of Object.entries({ tenantId, jobId: offerJob.id, name, email: `demo.approval-${key}@${slug}.example.test`, processingConsent: 'true', consentVersion: consent.version, receiptToken: randomUUID() + randomUUID(), answers: '{}' })) form.set(k, v);
        form.set('resume', new Blob([resumePdf([`${name} — SYNTHETIC LOCAL TEST DATA`, 'Backend Engineer, 5 years of experience.', 'Skills: Java, Spring Boot, Kafka, MongoDB, SQL, Docker.', 'Built production backend services and owned on-call delivery.', 'Payments systems design and API development. This is not a real person or employment history.'])], { type: 'application/pdf' }), 'demo-approval-resume.pdf');
        const application = await api('/public/applications', form, 'POST', {});
        state.applications[key] = application.id;
      }
      checkpoint(); console.log(`PASS: synthetic approval candidate ${key}`);
    }
  }
  const deadline = Date.now() + 240000;
  while (true) {
    const current = await rows();
    const waiting = Object.values(state.applications).filter(id => !current.some(r => r.applicationId === id && ['AI_ASSESSED', 'SHORTLISTED', 'INTERVIEWING', 'OFFER', 'HIRED'].includes(r.stage)));
    if (!waiting.length) break;
    assert(Date.now() < deadline, 'Screening is not ready. Resume the same refresh later; no model result or stage bypass was inserted');
    console.log(`Waiting for normal resume/AI screening: ${waiting.length} synthetic applications`);
    await new Promise(resolve => setTimeout(resolve, 10000));
  }
  for (const key of ['finance', 'approved']) {
    if (state.completed.includes(`offer-${key}`)) continue;
    const id = state.applications[key];
    let application = (await rows()).find(r => r.applicationId === id);
    if (application.stage === 'AI_ASSESSED') await api(`/applications/${id}/shortlist`, undefined, 'POST');
    application = (await rows()).find(r => r.applicationId === id);
    if (application.stage === 'SHORTLISTED') await api(`/applications/${id}/stage`, { stage: 'INTERVIEWING' }, 'PATCH');
    application = (await rows()).find(r => r.applicationId === id);
    if (application.stage === 'INTERVIEWING') await api(`/applications/${id}/decision`, { decision: 'HIRE', reason: 'Synthetic approval scenario only; not a real hiring decision.' }, 'POST');
    let offer = state.offers[key] ? await api(`/offers/${state.offers[key]}`) : await api(`/applications/${id}/offer`, {}, 'POST');
    state.offers[key] = offer.id; checkpoint();
    if (offer.status === 'DRAFT') {
      const day = n => new Date(Date.now() + n * 86400000).toISOString().slice(0, 10);
      offer = await api(`/offers/${offer.id}`, { terms: { base: key === 'finance' ? 4400000 : 4200000, bonus: key === 'finance' ? 200000 : 100000, joiningDate: day(30), expiryDate: day(7), reportsTo: 'Demo hiring manager', location: 'Pune, onsite — synthetic demo' }, coverNote: 'Synthetic approval testing only. Do not send as a real offer.' }, 'PATCH');
      offer = await api(`/offers/${offer.id}/submit`, { assignees: { HIRING_MANAGER: people.HIRING_MANAGER.id,
        HR_ADMIN: people.HR_ADMIN.id, FINANCE: people.ORG_ADMIN.id } }, 'POST');
    }
    while (offer.status === 'PENDING_APPROVAL' && offer.approvalSteps.some(s => s.status === 'CURRENT' && ['HIRING_MANAGER', 'HR_ADMIN'].includes(s.role))) {
      const step = offer.approvalSteps.find(s => s.status === 'CURRENT');
      offer = await api(`/offers/${offer.id}/approvals/${step.id}/approve`, undefined, 'POST', step.role === 'HIRING_MANAGER' ? asManager : asHr);
    }
    assert(key === 'finance' ? offer.status === 'PENDING_APPROVAL' && offer.approvalSteps.find(s => s.status === 'CURRENT')?.role === 'FINANCE' : offer.status === 'APPROVED', 'Unexpected offer state; not overwritten');
    done(`offer-${key}`);
  }
  const finance = await api(`/offers/${state.offers.finance}/approval-inbox`, undefined, 'GET', asOrgAdmin);
  const approved = await api(`/offers/${state.offers.approved}/approval-inbox`, undefined, 'GET', asHr);
  if (!state.completed.includes('verified')) {
    assert(finance.offerAction.canDecide && finance.terms.some(t => t.label === 'Above midpoint by' && t.value === 'INR 1,00,000'), 'Finance scenario not visible to tenant administrator');
    assert(approved.viewerDecision && approved.status === 'APPROVED', 'Approved offer history missing');
  }
  done('verified');
}
