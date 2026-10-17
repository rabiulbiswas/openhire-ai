// Opt-in live test: creates a development application, then withdraws it; audit data is retained.
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { staffToken } from './phase3-staff-token.mjs';
import { resumePdf, storedZip } from './phase3-pdf-fixture.mjs';

const gateway = process.env.OPENHIRE_GATEWAY_URL || 'http://localhost:8080';
const jobId = process.env.OPENHIRE_JOB_ID;
const tenant = process.env.OPENHIRE_TENANT_ID || 'demo-org';
assert(jobId, 'Set OPENHIRE_JOB_ID to an OPEN development job without eligibility questions');
let ready = false;
for (let attempt = 0; attempt < 30; attempt++) {
  try {
    const health = await fetch(`${gateway}/actuator/health`, { signal: AbortSignal.timeout(3000) });
    if (health.ok) { ready = true; break; }
  } catch { /* A restarting gateway may reset the connection before opening its port. */ }
  await new Promise(resolve => setTimeout(resolve, 2000));
}
assert(ready, 'Gateway did not become healthy within the startup window');
const request = async (path, options = {}, expected = 200) => {
  const response = await fetch(`${gateway}${path}`, { ...options, signal: AbortSignal.timeout(45000) });
  const text = await response.text();
  assert.equal(response.status, expected, `${options.method || 'GET'} ${path}: ${response.status} ${text.slice(0, 500)}`);
  return text ? JSON.parse(text) : null;
};
const token = await staffToken();
const auth = { Authorization: `Bearer ${token}` };
const csrfResponse = await fetch(`${gateway}/bff/csrf`, { headers: auth, signal: AbortSignal.timeout(15000) });
assert.equal(csrfResponse.status, 200, 'CSRF token endpoint failed');
const csrf = await csrfResponse.json();
auth.Cookie = csrfResponse.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
auth[csrf.headerName] = decodeURIComponent(csrfResponse.headers.getSetCookie().find(value => value.startsWith('XSRF-TOKEN=')).split(';')[0].slice('XSRF-TOKEN='.length));
const consent = await request('/api/v1/public/consent');
await request('/api/v1/applications', {}, 401);

// Small valid text PDF assembled in memory, with calculated xref offsets; no real personal data.
const text = 'BT /F1 12 Tf 50 750 Td (Runtime Fixture. Skills: Java Spring Boot MongoDB. Experience: backend engineer.) Tj ET';
const objects = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>', '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', `<< /Length ${Buffer.byteLength(text)} >>\nstream\n${text}\nendstream`];
let pdf = '%PDF-1.4\n';
const offsets = [0];
objects.forEach((value, index) => { offsets.push(Buffer.byteLength(pdf)); pdf += `${index + 1} 0 obj\n${value}\nendobj\n`; });
const xref = Buffer.byteLength(pdf);
pdf += `xref\n0 6\n0000000000 65535 f \n${offsets.slice(1).map(value => `${String(value).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
const key = randomUUID(), receipt = randomUUID() + randomUUID();
const form = () => {
  const body = new FormData();
  for (const [name, value] of Object.entries({ tenantId: tenant, jobId, name: 'Phase 3 Runtime Fixture', email: `runtime-${key}@example.test`, processingConsent: 'true', consentVersion: consent.version, receiptToken: receipt, answers: '{}' })) body.set(name, value);
  body.set('resume', new Blob([pdf], { type: 'application/pdf' }), 'runtime-fixture.pdf');
  return body;
};
const submit = () => request('/api/v1/public/applications', { method: 'POST', headers: { 'Idempotency-Key': key }, body: form() });
const application = await submit();
assert.equal((await submit()).id, application.id);
const invalidConsent = form();
invalidConsent.set('processingConsent', 'false');
await request('/api/v1/public/applications', { method: 'POST', headers: { 'Idempotency-Key': randomUUID() }, body: invalidConsent }, 400);
console.log(`PASS: submission/replay ${application.id}`);
const statusPath = `/api/v1/public/applications/${application.id}?tenantId=${encodeURIComponent(tenant)}`;
const receiptHeaders = { 'X-Application-Receipt': receipt };
await request(statusPath, { headers: { 'X-Application-Receipt': 'invalid' } }, 403);
let screened = false;
for (let i = 0; i < 90; i++) {
  const row = await request(statusPath, { headers: receiptHeaders });
  if (row.stage === 'AI_ASSESSED') { screened = true; break; }
  assert.notEqual(row.stage, 'NEEDS_ATTENTION', 'Processing needs attention; inspect scanner/extractor and service logs');
  await new Promise(resolve => setTimeout(resolve, 2000));
}
assert(screened, 'Screening did not finish within 180 seconds');
const assessment = await request(`/api/v1/applications/${application.id}/assessment`, { headers: auth });
assert.equal(typeof assessment.score, 'number');
const card = await request(`/api/v1/applications/${application.id}/shortlist`, { method: 'POST', headers: auth });
assert.equal(card.stage, 'SHORTLISTED');
await request(`/api/v1/applications/${application.id}/undo-decision`, { method: 'POST', headers: auth });
assert.equal((await request(statusPath, { headers: receiptHeaders })).stage, 'AI_ASSESSED');
let candidate;
for (let i = 0; i < 20; i++) {
  candidate = await request(`/api/v1/candidates/${card.candidateId}/360`, { headers: auth });
  if (candidate.applications?.some(row => row.applicationId === application.id || row.id === application.id)) break;
  await new Promise(resolve => setTimeout(resolve, 1000));
}
assert(candidate.applications?.some(row => row.applicationId === application.id || row.id === application.id), 'Candidate projection missing');
for (const document of candidate.documents || []) {
  const download = await fetch(`${gateway}/api/v1/resumes/${document.id}/download`, { headers: auth, signal: AbortSignal.timeout(15000) });
  assert.equal(download.status, 200);
  assert((await download.text()).startsWith('%PDF-'), 'Resume download is not the uploaded PDF');
}
await request(`/api/v1/public/applications/${application.id}/withdraw?tenantId=${encodeURIComponent(tenant)}`, { method: 'POST', headers: receiptHeaders });
assert.equal((await request(statusPath, { headers: receiptHeaders })).stage, 'WITHDRAWN');
console.log('PASS: real scanner/extraction, screening, authorized decisions/undo, Candidate 360 and receipt withdrawal');
const importBody = new FormData();
importBody.set('jobId', jobId);
importBody.set('processingConsent', 'true');
importBody.set('consentVersion', consent.version);
importBody.set('file', new Blob([`name,email,processingConsent\nCSV Fixture,csv-${key}@example.test,true\nNo Consent,skip-${key}@example.test,false\nInvalid Email,invalid,true\n`], { type: 'text/csv' }), 'runtime-fixture.csv');
async function finishedImport(body) {
  const started = await request('/api/v1/imports', { method: 'POST', headers: { ...auth, 'Idempotency-Key': randomUUID() }, body });
  let result;
  for (let attempt = 0; attempt < 30; attempt++) {
    result = await request(`/api/v1/imports/${started.id}`, { headers: auth });
    if (result.status === 'DONE') return result;
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  assert.fail(`Import ${started.id} did not finish: ${JSON.stringify(result)}`);
}
const report = await finishedImport(importBody);
assert.equal(report.totals.imported, 1);
assert.equal(report.totals.skippedNoConsent, 1);
assert.equal(report.totals.failed, 1);
console.log('PASS: durable CSV import, consent skip and failed-row reporting');
const zipBody = new FormData();
zipBody.set('jobId', jobId);
zipBody.set('processingConsent', 'true');
zipBody.set('consentVersion', consent.version);
zipBody.set('file', new Blob([storedZip({
  'candidates.csv': `name,email,processingConsent,resume\nZIP Fixture,zip-${key}@example.test,true,zip-fixture.pdf\nZIP Missing File,zip-missing-${key}@example.test,true,absent.pdf\n`,
  'zip-fixture.pdf': resumePdf(),
})], { type: 'application/zip' }), 'runtime-fixture.zip');
const zipReport = await finishedImport(zipBody);
assert.equal(zipReport.totals.imported, 1);
assert.equal(zipReport.totals.failed, 1);
assert.match(zipReport.failedRows[0].reason, /missing from ZIP/);
console.log('PASS: ZIP import with referenced resume and missing-file row reporting');
console.log('Development application and resume retained for audit; no existing records deleted.');
