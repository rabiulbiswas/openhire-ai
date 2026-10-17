import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { affected, changedFiles, inventory } from './affected.mjs';
import { validateImages } from './release.mjs';

export function releaseOverrides(manifest, current, required, registryPrefix) {
  validateImages(manifest.images, manifest.services, manifest.sha, registryPrefix);
  const missing = required.filter(name => !manifest.services.includes(name));
  if (missing.length) throw Error(`Release omits changes since last deployment: ${missing.join(', ')}. Run full CI manually, then deploy its release.`);
  const services = {};
  for (const image of manifest.images) {
    if (!current.services?.[image.service]) throw Error(`Existing Helm release does not define ${image.service}; review/bootstrap chart first`);
    services[image.service] = { image: { repository: image.repository, digest: image.digest } };
  }
  return { services, ci: { sourceSha: manifest.sha, runId: manifest.runId } };
}

function execute(command, args, capture = false) {
  return execFileSync(command, args, { encoding: 'utf8', stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit' });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const { HELM_RELEASE: release, K8S_NAMESPACE: namespace, ECR_PREFIX: prefix, SOURCE_SHA: sha, SOURCE_RUN: runId } = process.env;
  if (!/^[a-z][a-z0-9-]{0,29}$/.test(release || '') || !/^[a-z][a-z0-9-]{0,62}$/.test(namespace || '')) throw Error('Set valid HELM_RELEASE and K8S_NAMESPACE');
  if (!/^\d{12}\.dkr\.ecr\.ap-south-2\.amazonaws\.com\/openhire$/.test(prefix || '')) throw Error('Set ECR_PREFIX to the approved Hyderabad registry/openhire');
  const manifest = JSON.parse(fs.readFileSync('release-input/release.json', 'utf8'));
  if (manifest.sha !== sha || manifest.runId !== runId) throw Error('Artifact does not belong to the approved CI run');
  execute('git', ['merge-base', '--is-ancestor', sha, 'origin/main']);
  const current = JSON.parse(execute('helm', ['get', 'values', release, '-n', namespace, '--all', '-o', 'json'], true));
  const base = current.ci?.sourceSha;
  if (base) {
    if (!/^[a-f0-9]{40}$/.test(base)) throw Error('Invalid deployed source SHA');
    execute('git', ['merge-base', '--is-ancestor', base, sha]); // No stale/out-of-order rollback.
  }
  const required = base ? affected(changedFiles(base, sha)).services : inventory().services;
  const overrides = releaseOverrides(manifest, current, required, prefix);
  const active = manifest.services.filter(name => current.services[name].enabled);
  // Fail before upgrade when a workload's configured Secret is absent. Values are never read/logged.
  for (const service of active) execute('kubectl', ['get', 'secret', `${release}-${service}`, '-n', namespace, '-o', 'name'], true);
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'openhire-release-'));
  const overridePath = path.join(directory, 'images.json');
  fs.writeFileSync(overridePath, JSON.stringify(overrides), { mode: 0o600 });
  const summary = process.env.GITHUB_STEP_SUMMARY;
  try {
    if (summary) fs.appendFileSync(summary, `## Deployment plan\n\nEnvironment: ${process.env.DEPLOY_ENVIRONMENT}\n\n| Service | Image digest | Action |\n| --- | --- | --- |\n${manifest.images.map(i => `| ${i.service} | \`${i.digest}\` | ${current.services[i.service].enabled ? 'roll out if changed' : 'record image; workload disabled'} |`).join('\n')}\n\nUnchanged service image values are preserved. Helm may also apply reviewed chart changes.\n`);
    execute('helm', ['upgrade', release, 'backend/infra/helm/openhire', '-n', namespace,
      '--reuse-values', '-f', overridePath, '--atomic', '--wait', '--timeout', '15m', '--history-max', '10']);
    for (const service of active) execute('kubectl', ['rollout', 'status', `deployment/${release}-${service}`, '-n', namespace, '--timeout=180s']);
    if (summary) fs.appendFileSync(summary, '\n**Rollout completed.** Readiness is verified; business workflow acceptance remains a separate gate.\n');
  } catch (error) {
    // Show status, not pod environment or application logs that might expose credentials/PII.
    try { execute('kubectl', ['get', 'pods', '-n', namespace, '-l', `app.kubernetes.io/instance=${release}`, '-o', 'wide']); } catch {}
    if (summary) fs.appendFileSync(summary, '\n**Deployment failed.** Helm atomic upgrade attempts to restore the prior release on upgrade failure; inspect release status. No database rollback is performed.\n');
    throw error;
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
