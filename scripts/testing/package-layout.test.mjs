import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkPackageLayout } from '../maintenance/package-layout.mjs';

test('all backend services follow the package layout without changing domain identities or HTTP mappings', () => {
  const result = checkPackageLayout();
  assert.equal(result.services, 18);
  assert.deepEqual(result.errors, []);
});
