// Owner-run k6 test. Creates real applications; use a dedicated development tenant/job.
import http from 'k6/http';
import { check } from 'k6';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import exec from 'k6/execution';
import { resumePdf } from './phase3-pdf-fixture.mjs';

if (!__ENV.OPENHIRE_JOB_ID || !__ENV.OPENHIRE_TENANT_ID) throw new Error('Set OPENHIRE_JOB_ID and OPENHIRE_TENANT_ID');
const resume = __ENV.OPENHIRE_RESUME ? open(__ENV.OPENHIRE_RESUME, 'b') : resumePdf();
const gateway = __ENV.OPENHIRE_GATEWAY_URL || 'http://localhost:8080';
const run = Date.now().toString(36);
export const options = {
  scenarios: {
    sustained: { executor: 'constant-arrival-rate', rate: 500, timeUnit: '1m', duration: '5m', preAllocatedVUs: 20, maxVUs: 100 },
    burst: { executor: 'constant-arrival-rate', rate: 20, timeUnit: '1s', duration: '10s', startTime: '5m', preAllocatedVUs: 30, maxVUs: 100 },
  },
  thresholds: { checks: ['rate==1'], 'http_req_failed{phase:sustained}': ['rate<0.01'], 'http_req_duration{phase:sustained}': ['p(95)<5000'], dropped_iterations: ['count==0'] },
};
export function setup() {
  const response = http.get(`${gateway}/api/v1/public/consent`);
  if (response.status !== 200) throw new Error('Consent service unavailable');
  return { consentVersion: response.json('version') };
}
export default function (data) {
  const key = `${run}-${exec.scenario.name}-${exec.scenario.iterationInTest}`;
  const response = http.post(`${gateway}/api/v1/public/applications`, {
    tenantId: __ENV.OPENHIRE_TENANT_ID, jobId: __ENV.OPENHIRE_JOB_ID,
    name: `Load fixture ${key}`, email: `load-${key}@example.test`, processingConsent: 'true', consentVersion: data.consentVersion,
    receiptToken: encoding.b64encode(crypto.randomBytes(48), 'rawurl'), answers: __ENV.OPENHIRE_ANSWERS || '{}',
    resume: http.file(resume, 'load-fixture.pdf', 'application/pdf'),
  }, { headers: { 'Idempotency-Key': key }, tags: { phase: exec.scenario.name }, timeout: '30s' });
  check(response, { 'accepted or deliberately rate-limited during burst': r => r.status === 200 || (exec.scenario.name === 'burst' && r.status === 429) });
}
