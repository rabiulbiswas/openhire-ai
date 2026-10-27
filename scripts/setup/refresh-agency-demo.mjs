import assert from 'node:assert/strict';
import { resumePdf } from '../testing/phase3-pdf-fixture.mjs';

// Additive agency fixtures: normal tenant APIs own validation, deduplication and processing.
export async function refreshAgencyDemo({ api, people, asRecruiter, asAgency, manifest, checkpoint, tenantId, slug, tenantName }) {
  assert(manifest.tenantId === tenantId && manifest.tag === `${slug}-demo-v1`, 'Seed manifest tenant mismatch');
  assert(people.AGENCY_RECRUITER?.id, 'Existing demo agency recruiter required');
  const page = await api('/partner/jobs?limit=1', undefined, 'GET', asAgency);
  assert(Array.isArray(page.data) && typeof page.hasMore === 'boolean', 'Rebuild application-service for the paginated agency contract');
  const jobs = [];
  for (const label of ['Backend Engineer', 'Data Engineer']) {
    const saved = manifest.jobs.find(j => j.label === label);
    assert(saved, `Original demo ${label} job missing`);
    const job = await api(`/jobs/${saved.id}`, undefined, 'GET', asRecruiter);
    assert(job.title === `[DEMO:${slug.toUpperCase()}] ${label}` && job.status === 'OPEN', 'Demo job changed or is not open; refusing to overwrite it');
    jobs.push(job);
  }
  const consent = await api(`/public/consent?tenantId=${encodeURIComponent(tenantId)}`, undefined, 'GET', asAgency);
  assert(consent.version, 'Current consent policy unavailable');
  manifest.agencyRefresh ??= { tag: `${slug}-agency-v1`, assignments: [], applications: [] };
  const state = manifest.agencyRefresh;
  for (const job of jobs) {
    if (state.assignments.includes(job.id)) continue; // Do not undo later assignment changes.
    await api(`/partner-assignments/${people.AGENCY_RECRUITER.id}/${job.id}`, { active: true }, 'PUT', asRecruiter);
    state.assignments.push(job.id); checkpoint(); console.log('PASS: existing demo job assigned to agency recruiter');
  }
  const periods = ['IMMEDIATE', '15', '30', '60', '90', 'MORE_THAN_90'];
  for (let index = 0; index < periods.length; index++) {
    const key = `agency-${index + 1}`;
    if (state.applications.some(a => a.key === key)) continue;
    const job = jobs[index % jobs.length];
    const name = `${tenantName} Demo Agency Candidate ${index + 1}`;
    const email = `demo.${key}@${slug}.example.test`;
    const form = new FormData();
    for (const [field, value] of Object.entries({ jobId: job.id, source: 'AGENCY', name, email,
      phone: `+1 202 555 01${String(index).padStart(2, '0')}`, noticePeriod: periods[index],
      candidateReason: 'Synthetic local agency demo: backend delivery with Java, Spring Boot and Kafka. Not a real candidate or hiring commitment.',
      externalReference: `${state.tag}:${key}`, processingConsent: 'true', consentVersion: consent.version, answers: '{}' })) form.set(field, value);
    form.set('resume', new Blob([resumePdf([`${name} - SYNTHETIC LOCAL TEST DATA`,
      'Backend Engineer - 5 years of synthetic experience.', 'Skills: Java, Spring Boot, Kafka, MongoDB, SQL, Docker.',
      'Built REST APIs and automated tests. Education: BSc Computer Science.', 'Not a real person or employment history.'])], { type: 'application/pdf' }), 'agency-demo-resume.pdf');
    const app = await api('/applications', form, 'POST', asAgency);
    assert(app.id, 'Agency intake did not return an application ID');
    state.applications.push({ key, id: app.id, email, jobId: job.id }); checkpoint(); console.log(`PASS: synthetic ${key} submission saved`);
  }
  // Exercise next-page reads and check public data without manufacturing workflow states.
  const submissions = [];
  let after;
  do {
    const value = await api(`/partner/submissions?limit=2${after ? `&after=${encodeURIComponent(after)}` : ''}`, undefined, 'GET', asAgency);
    assert(Array.isArray(value.data), 'Agency submission page unavailable');
    submissions.push(...value.data);
    after = value.hasMore ? value.nextCursor : null;
    assert(!value.hasMore || after, 'Missing pagination cursor');
    assert(submissions.length <= 10000, 'Unexpected agency pagination loop');
  } while (after);
  assert(state.applications.every(a => submissions.some(s => s.id === a.id)), 'Seeded agency submissions are not visible to their owner');
  const assigned = await api('/partner/jobs?limit=100', undefined, 'GET', asAgency);
  assert(jobs.every(j => assigned.data.some(a => a.id === j.id && a.submitted >= 3)), 'Assigned job submission counts not synchronized');
  return { assignedJobs: jobs.length, submissions: state.applications.length, statuses: state.applications.map(a => ({ id: a.id, status: submissions.find(s => s.id === a.id).stage })), paginationVerified: true };
}
