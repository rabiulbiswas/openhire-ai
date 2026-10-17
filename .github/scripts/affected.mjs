import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export function inventory(root = '.') {
  const modules = [...fs.readFileSync(`${root}/settings.gradle`, 'utf8').matchAll(/include\s+'([^']+)'/g)].map(m => m[1]);
  const dependencies = Object.fromEntries(modules.map(name => [name,
    [...fs.readFileSync(`${root}/${name}/build.gradle`, 'utf8').matchAll(/project\(['"]:([^'"]+)['"]\)/g)].map(m => m[1])
  ]));
  return { modules, dependencies, services: modules.filter(name => !['platform-starter', 'service-support'].includes(name)) };
}

export function affected(files, all = false, root = '.') {
  const graph = inventory(root);
  const selected = new Set();
  let chart = all;
  for (const file of files) {
    if (file.startsWith('infra/helm/')) chart = true;
    if (/^\.github\//.test(file) || /^(Dockerfile[^/]*|build\.gradle|settings.*|gradle\.properties|gradlew.*|gradle\/.*)$/.test(file)) {
      all = true;
    }
    const module = file.split('/')[0];
    const modulePath = file.split('/').slice(1).join('/');
    const documentationOnly = modulePath.startsWith('docs/') || /^[^/]+\.md$/.test(modulePath);
    // Markdown under src can be a prompt/resource and must rebuild its owner.
    if (graph.modules.includes(module) && !documentationOnly) selected.add(module);
    if (file.startsWith('contracts/')) all = true;
  }
  if (all) graph.modules.forEach(name => selected.add(name));
  let changed = true;
  while (changed) {
    changed = false;
    for (const name of graph.modules) if (!selected.has(name) && graph.dependencies[name].some(dep => selected.has(dep))) {
      selected.add(name); changed = true;
    }
  }
  return { services: graph.services.filter(name => selected.has(name)), chart };
}

export function changedFiles(base, head = 'HEAD') {
  return execFileSync('git', ['diff', '--name-only', '--no-renames', '-z', base, head], { encoding: 'utf8' }).split('\0').filter(Boolean);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  const sha = process.env.GITHUB_SHA;
  const base = event.pull_request?.base.sha || event.before;
  const full = process.env.GITHUB_EVENT_NAME === 'workflow_dispatch' || !base || /^0+$/.test(base);
  const result = affected(full ? [] : changedFiles(base, sha), full);
  const outputs = { ...result, backend: result.services.length > 0 };
  for (const [key, value] of Object.entries(outputs)) fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${JSON.stringify(value)}\n`);
  fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `## Change detection\n\nBackend services: ${result.services.join(', ') || 'none'}\n\nHelm: ${result.chart ? 'changed' : 'unchanged'}\n\nShared dependencies and contracts expand the build set. Manual runs perform a full build.\n`);
}
