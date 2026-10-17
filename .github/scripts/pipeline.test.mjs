import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { affected, inventory } from './affected.mjs';
import { validateImages } from './release.mjs';
import { releaseOverrides } from './deploy.mjs';

test('GitHub change detection CLI runs at the standalone repository root', () => {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'openhire-ci-root-'));
  try {
    const event = path.join(temp, 'event.json');
    const output = path.join(temp, 'outputs');
    const summary = path.join(temp, 'summary');
    fs.writeFileSync(event, '{}');
    execFileSync(process.execPath, ['.github/scripts/affected.mjs'], {
      env: { ...process.env, GITHUB_EVENT_PATH: event, GITHUB_EVENT_NAME: 'workflow_dispatch',
        GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary },
    });
    const values = Object.fromEntries(fs.readFileSync(output, 'utf8').trim().split('\n').map(line => {
      const index = line.indexOf('=');
      return [line.slice(0, index), JSON.parse(line.slice(index + 1))];
    }));
    assert.deepEqual(values.services, inventory().services);
    assert.equal(values.backend, true);
    assert.equal('frontend' in values, false);
  } finally { fs.rmSync(temp, { recursive: true, force: true }); }
});

test('CI uses standalone paths and does not require the frontend repository', () => {
  const workflow = fs.readFileSync('.github/workflows/ci.yml', 'utf8');
  assert.doesNotMatch(workflow, /backend\/|frontend\/|working-directory: backend|^  frontend:/m);
  assert.match(workflow, /node --test .* scripts\/deploy-local.test.mjs scripts\/mongo-recovery.test.mjs/);
  assert.equal((workflow.match(/if: \$\{\{ false \}\}/g) || []).length, 2);
  assert.match(fs.readFileSync('.github/workflows/deploy.yml', 'utf8'), /if: \$\{\{ false \}\}/);
});

test('every executable backend module has a change-triggered build', () => {
  assert.equal(inventory().services.length, 18);
  for (const service of inventory().services) assert.deepEqual(affected([`${service}/src/main/java/Changed.java`]).services, [service]);
});
test('platform changes propagate transitively without rebuilding independent gateway', () => {
  const services = affected(['platform-starter/src/main/java/Tenant.java']).services;
  assert.equal(services.length, 17);
  assert.ok(services.includes('integration-hub'));
  assert.ok(!services.includes('api-gateway'));
});
test('service-support changes affect only its consumers', () => {
  const services = affected(['service-support/build.gradle']).services;
  assert.ok(services.includes('application-service'));
  assert.ok(!services.includes('job-service'));
  assert.ok(!services.includes('identity-service'));
});
test('build tooling, workflow, contract and initial/manual changes are conservative', () => {
  for (const file of ['build.gradle', 'settings.gradle', 'gradle/libs.versions.toml', 'Dockerfile.ci', '.github/workflows/ci.yml', 'contracts/phase3-api.openapi.json']) {
    assert.equal(affected([file]).services.length, 18, file);
  }
  assert.equal(affected([], true).services.length, 18);
});
test('docs-only edits skip backend; chart-only changes preserve image build selection', () => {
  assert.deepEqual(affected(['docs/PHASE_0.md']).services, []);
  assert.deepEqual(affected(['job-service/README.md']).services, []);
  assert.deepEqual(affected(['job-service/src/main/resources/prompts/draft.md']).services, ['job-service']);
  assert.deepEqual(affected(['infra/helm/openhire/values.yaml']), { services: [], chart: true });
});
test('renames/deletions can affect both old and new owners', () => {
  assert.deepEqual(affected(['job-service/src/Old.java', 'approval-service/src/New.java']).services.sort(), ['approval-service', 'job-service']);
});

const sha = 'a'.repeat(40);
const prefix = '123456789012.dkr.ecr.ap-south-2.amazonaws.com/openhire';
const image = { service: 'job-service', repository: `${prefix}/job-service`, digest: `sha256:${'b'.repeat(64)}`, sha };
test('release validation rejects missing, duplicate, untrusted and mismatched artifacts', () => {
  validateImages([image], ['job-service'], sha, prefix);
  for (const images of [[], [image, image], [{...image, sha:'c'.repeat(40)}], [{...image, digest:'latest'}], [{...image, repository:'attacker/job-service'}], [{...image, repository:prefix.replace('123456789012','999999999999')+'/job-service'}]]) {
    assert.throws(() => validateImages(images, ['job-service'], sha, prefix));
  }
});
test('deployment overrides touch only selected image fields and fail on missed releases', () => {
  const manifest = {images:[image], services:['job-service'], sha, runId:'123'};
  const current = {services: {'job-service': {enabled:true, replicas:3}, 'identity-service': {enabled:true}}};
  const overrides = releaseOverrides(manifest, current, ['job-service'], prefix);
  assert.deepEqual(Object.keys(overrides.services), ['job-service']);
  assert.equal(overrides.services['job-service'].image.digest, image.digest);
  assert.equal(overrides.services['job-service'].replicas, undefined);
  assert.equal(overrides.ci.sourceSha, sha);
  assert.throws(() => releaseOverrides(manifest, current, ['job-service','identity-service'], prefix), /omits changes/);
  assert.throws(() => releaseOverrides(manifest, {services:{}}, [], prefix), /does not define/);
});
test('Helm digest override preserves other service image tags', () => {
  const output = execFileSync('helm', ['template','openhire','infra/helm/openhire','--set','image.tag=baseline',
    '--set',`services.job-service.image.repository=${prefix}/job-service`, '--set',`services.job-service.image.digest=${image.digest}`], {encoding:'utf8'});
  assert.ok(output.includes(`image: "${prefix}/job-service@${image.digest}"`));
  assert.ok(output.includes('image: "openhire/identity-service:baseline"'));
  assert.ok(!output.includes('image: "openhire/job-service:baseline"'));
});
