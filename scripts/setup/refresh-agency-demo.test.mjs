import test from 'node:test';
import assert from 'node:assert/strict';
import { refreshAgencyDemo } from './refresh-agency-demo.mjs';

function fixture() {
  const manifest = { tenantId: 'tenant', tag: 'codelogicx-demo-v1', jobs: [{ id: 'backend', label: 'Backend Engineer' }, { id: 'data', label: 'Data Engineer' }] };
  const submissions = [];
  const writes = [];
  const api = async (path, body, method, auth) => {
    if (method !== 'GET') writes.push({ path, body, auth });
    if (path.startsWith('/jobs/')) return { id: path.slice(6), title: `[DEMO:CODELOGICX] ${path.endsWith('backend') ? 'Backend Engineer' : 'Data Engineer'}`, status: 'OPEN' };
    if (path.startsWith('/partner/jobs')) return { data: ['backend', 'data'].map(id => ({ id, submitted: submissions.filter(s => s.jobId === id).length })), hasMore: false };
    if (path.startsWith('/public/consent')) return { version: 'current-policy' };
    if (path.startsWith('/partner-assignments/')) return {};
    if (path === '/applications') {
      assert.equal(auth, 'agency-auth');
      assert.equal(body.get('source'), 'AGENCY');
      assert.equal(body.get('consentVersion'), 'current-policy');
      assert.equal(body.get('processingConsent'), 'true');
      assert.ok(body.get('externalReference').startsWith('codelogicx-agency-v1:'));
      assert.ok(body.get('resume').size > 0);
      const app = { id: `app-${submissions.length}`, stage: 'RECEIVED', jobId: body.get('jobId') };
      submissions.push(app); return app;
    }
    if (path.startsWith('/partner/submissions')) {
      const start = Number(new URL(path, 'http://test').searchParams.get('after') || 0);
      return { data: submissions.slice(start, start + 2), hasMore: start + 2 < submissions.length, nextCursor: String(start + 2) };
    }
    assert.fail(`Unexpected request ${path}`);
  };
  return { manifest, writes, args: { api, manifest, people: { AGENCY_RECRUITER: { id: 'demo-agency' } }, asRecruiter: 'staff-auth', asAgency: 'agency-auth', checkpoint: () => {}, tenantId: 'tenant', slug: 'codelogicx', tenantName: 'CodeLogicx' } };
}

test('agency seed uses existing jobs, validated agency intake and real pagination; rerun adds no duplicates', async () => {
  const f = fixture();
  assert.deepEqual(await refreshAgencyDemo(f.args), { assignedJobs: 2, submissions: 6, statuses: Array.from({ length: 6 }, (_, i) => ({ id: `app-${i}`, status: 'RECEIVED' })), paginationVerified: true });
  assert.equal(f.writes.length, 8);
  const before = structuredClone(f.manifest);
  f.writes.length = 0;
  await refreshAgencyDemo(f.args);
  assert.equal(f.writes.length, 0);
  assert.deepEqual(f.manifest, before);
});

test('wrong tenant and changed demo jobs fail before writes', async () => {
  const f = fixture();
  await assert.rejects(refreshAgencyDemo({ ...f.args, tenantId: 'other' }), /tenant mismatch/);
  await assert.rejects(refreshAgencyDemo({ ...f.args, api: async (path, ...args) => path.startsWith('/jobs/') ? { title: 'Real job', status: 'OPEN' } : f.args.api(path, ...args) }), /refusing to overwrite/);
  assert.equal(f.writes.length, 0);
  assert.equal(f.manifest.agencyRefresh, undefined);
});
