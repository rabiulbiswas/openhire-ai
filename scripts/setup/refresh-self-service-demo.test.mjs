import test from 'node:test';
import assert from 'node:assert/strict';
import { refreshSelfServiceDemo } from './refresh-self-service-demo.mjs';

test('self-service refresh uses existing employee and jobs, scoped receipts, real intake and repeat-safe checkpoints', async () => {
  const manifest = { tenantId: 'tenant', tag: 'codelogicx-demo-v1', jobs: [{ id: 'backend', label: 'Backend Engineer' }, { id: 'data', label: 'Data Engineer' }] };
  const candidates = new Map(); const employeeRows = []; const writes = [];
  const api = async (path, body, method, auth, headers) => {
    if (method !== 'GET') writes.push(path);
    if (path.startsWith('/me/referrals')) {
      assert.equal(auth, 'employee'); const index = Number(new URL(path, 'http://test').searchParams.get('after') || 0);
      return { data: employeeRows.slice(index, index + 2), hasMore: index + 2 < employeeRows.length, nextCursor: String(index + 2) };
    }
    if (path === '/me/onboarding') return { employeeNo: 'EMP-DEMO', tasks: [{ id: 't', done: true }] };
    if (path === '/internal-jobs') return { data: [{ id: 'backend' }] };
    if (path.startsWith('/jobs/')) return { id: path.slice(6), title: `[DEMO:CODELOGICX] ${path.endsWith('backend') ? 'Backend Engineer' : 'Data Engineer'}`, status: 'OPEN' };
    if (path.startsWith('/public/consent')) return { version: 'policy-v1' };
    if (path === '/public/applications' || path === '/applications') {
      assert.equal(body.get('processingConsent'), 'true'); assert.equal(body.get('consentVersion'), 'policy-v1');
      assert.ok(body.get('resume').size > 0);
      const id = `app-${candidates.size + employeeRows.length}`;
      if (path === '/public/applications') candidates.set(id, { name: body.get('name'), email: body.get('email'), token: body.get('receiptToken') });
      else { assert.equal(auth, 'employee'); employeeRows.push({ id, stage: 'RECEIVED' }); }
      return { id };
    }
    if (path.startsWith('/public/applications/')) {
      const row = candidates.get(path.split('/')[3].split('?')[0]); assert.equal(headers['X-Application-Receipt'], row.token); return row;
    }
    if (path === '/conversations') return { id: body.applicationId };
    if (path.startsWith('/conversations/')) { assert.equal(auth, 'recruiter'); return {}; }
    assert.fail(path);
  };
  const args = { api, manifest, people: { EMPLOYEE: { id: 'employee-id', username: 'demo.employee@codelogicx.example.test' } }, asEmployee: 'employee', asRecruiter: 'recruiter', checkpoint: () => {}, tenantId: 'tenant', slug: 'codelogicx', tenantName: 'CodeLogicx' };
  const result = await refreshSelfServiceDemo(args);
  assert.deepEqual(result, { candidateApplications: 2, employeeSubmissions: 3, employeeOnboardingTasks: 1, messages: 2, paginationVerified: true });
  const before = structuredClone(manifest); writes.length = 0;
  await refreshSelfServiceDemo(args);
  assert.deepEqual(writes, []); assert.deepEqual(manifest, before);
  await assert.rejects(refreshSelfServiceDemo({ ...args, tenantId: 'wrong' }), /tenant mismatch/);
});
