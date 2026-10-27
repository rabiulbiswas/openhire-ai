import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

test('job route preserves the public host without removing token relay or rate limiting', () => {
  const config = readFileSync(new URL('../../api-gateway/src/main/resources/application.yml', import.meta.url), 'utf8');
  const route = config.split('            - id: job-service\n')[1]?.split('            - id: ')[0];
  assert.ok(route, 'Job service route must exist');
  assert.match(route, /- PreserveHostHeader/);
  assert.match(route, /- TokenRelay/);
  assert.match(route, /name: RequestRateLimiter/);
});
