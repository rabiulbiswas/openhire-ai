import test from 'node:test';
import assert from 'node:assert/strict';
import { refreshApprovalDemo } from './refresh-approval-demo.mjs';

test('an unavailable new contract fails before any business or manifest writes', async () => {
  const calls = [];
  const manifest = { jobs: [{ id: 'original' }] };
  await assert.rejects(refreshApprovalDemo({ manifest, slug: 'codelogicx', api: async (...args) => {
    calls.push(args); return {}; // old server does not expose the offer-inbox contract
  }, checkpoint: () => assert.fail('must not write the manifest') }), /Offer inbox contract unavailable/);
  assert.equal(calls.length, 1);
  assert.equal(calls[0][2], undefined);
  assert.equal(manifest.approvalRefresh, undefined);
});

test('a completed refresh is read-only and does not undo later user decisions', async () => {
  const manifest = { jobs: [{ id: 'original' }], approvalRefresh: {
    tag: 'codelogicx-approvals-v2', jobs: { newJob: 'sre', versionChange: 'java', offerReview: 'offer-job' },
    requests: {}, applications: { finance: 'app-finance', approved: 'app-approved' }, offers: { finance: 'finance', approved: 'approved' },
    completed: ['new-job', 'headcount', 'version-change', 'offer-job', 'offer-finance', 'offer-approved', 'verified'],
  } };
  const calls = [];
  const before = structuredClone(manifest);
  const api = async (path, body, method) => {
    calls.push(path);
    assert.ok(!method || method === 'GET', 'completed seed must not mutate any business record');
    assert.equal(body, undefined);
    if (path === '/offers/approval-inbox?scope=requested_by_me') return { data: [] };
    if (path === '/jobs/original') return { currentVersion: { headcount: null } };
    if (path === '/approvals?scope=requested_by_me') return { data: [{ id: 'original-request' }] };
    if (path === '/approvals/original-request') return { headcountException: null };
    if (path.startsWith('/jobs/')) return { id: path.slice(6), title: 'Previously seeded job' };
    if (path.startsWith('/public/consent')) return { version: 'v1' };
    if (path.startsWith('/applications?')) return { data: [{ applicationId: 'app-finance', stage: 'OFFER' }, { applicationId: 'app-approved', stage: 'OFFER' }] };
    // A user already approved/revised these after the seed: do not reset or reapprove them.
    if (path.startsWith('/offers/')) return { status: 'SENT', offerAction: { canDecide: false }, viewerDecision: null };
    assert.fail(`Unexpected request ${path}`);
  };
  await refreshApprovalDemo({ api, manifest, checkpoint: () => {}, tenantId: 'tenant-a', slug: 'codelogicx' });
  assert.deepEqual(manifest, before);
  assert.ok(calls.includes('/offers/finance/approval-inbox'));
});
