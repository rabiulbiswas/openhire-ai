import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// Read-only: fail before creating users, jobs or candidate records if dependencies are unavailable.
export function checkPhase7Dependencies(urls) {
  for (const [name, value] of Object.entries(urls)) {
    const url = new URL(value);
    assert(['localhost', '127.0.0.1', '[::1]'].includes(url.hostname), `${name} must be local for fixture acceptance`);
  }
  const compose = fileURLToPath(new URL('../../docker-compose.yml', import.meta.url));
  const output = execFileSync('docker', ['compose', '-f', compose, 'ps', '--all', '--format', 'json'], { encoding: 'utf8' });
  const rows = output.trim().startsWith('[')
    ? JSON.parse(output)
    : output.trim().split('\n').filter(Boolean).map(line => JSON.parse(line));
  const required = ['keycloak', 'kafka', 'redis', 'clamav', 'tika', 'mailpit', 'api-gateway',
    'identity-service', 'job-service', 'approval-service', 'application-service', 'candidate-service',
    'resume-processor', 'interview-service', 'offer-service', 'employee-service', 'communication-service',
    'notification-dispatcher', 'ai-gateway', 'ai-workers', 'talent-service', 'compliance-service', 'analytics-service'];
  const failures = required.flatMap(service => {
    const row = rows.find(item => item.Service === service);
    return !row || row.State !== 'running' || row.Health !== 'healthy'
      ? [`${service}: ${row ? `${row.State}, ${row.Health || 'no health result'}` : 'missing'}`] : [];
  });
  assert.equal(failures.length, 0,
    `Phase 7 dependencies are not ready; no fixtures created:\n${failures.join('\n')}\nCheck container logs and Docker memory before retrying.`);
}
