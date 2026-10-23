import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
function javaFiles(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(directory, entry.name);
    return entry.isDirectory() ? javaFiles(file) : file.endsWith('.java') ? [file] : [];
  });
}

export function checkPackageLayout() {
  const baseline = JSON.parse(fs.readFileSync(path.join(root, 'docs/architecture/package-refactor-baseline.json'), 'utf8'));
  const modules = [...fs.readFileSync(path.join(root, 'settings.gradle'), 'utf8').matchAll(/^include '([^']+)'/gm)].map(m => m[1]);
  const entries = modules.flatMap(module => javaFiles(path.join(root, module, 'src/main/java')).map(file => {
    const source = fs.readFileSync(file, 'utf8');
    const pkg = source.match(/^package ([\w.]+);/m)?.[1];
    return { module, file: path.relative(root, file), source, pkg, name: path.basename(file, '.java'), fqn: `${pkg}.${path.basename(file, '.java')}` };
  }));
  const types = new Map(entries.map(e => [e.fqn, e]));
  const exceptions = new Set(baseline.protectedTypes.map(e => e.fqn));
  const errors = [];
  for (const entry of entries) {
    const expected = `/src/main/java/${entry.pkg?.replaceAll('.', '/')}/${entry.name}.java`;
    if (!entry.pkg || !entry.file.endsWith(expected)) errors.push(`Package/path mismatch: ${entry.file}`);
    if (['platform-starter', 'service-support'].includes(entry.module)) continue;
    if (!exceptions.has(entry.fqn) && entry.pkg.split('.').some(p => ['adapter', 'application', 'api', 'workflow'].includes(p))) errors.push(`Legacy package outside the persistence compatibility list: ${entry.fqn}`);
    if (/@RestController\b/.test(entry.source) && !entry.pkg.split('.').includes('controller')) errors.push(`Controller outside controller package: ${entry.fqn}`);
    if (entry.pkg.split('.').includes('service') && /^import com\.openhire\.[^;]*\.controller\./m.test(entry.source)) errors.push(`Service depends on controller: ${entry.fqn}`);
  }
  for (const entry of baseline.protectedTypes) {
    if (!types.has(entry.fqn) || types.get(entry.fqn).file !== entry.file) errors.push(`Persisted/domain type moved: ${entry.fqn}`);
  }
  for (const move of baseline.moves) {
    if (types.has(move.from)) errors.push(`Old type still present: ${move.from}`);
    if (!types.has(move.to)) errors.push(`Refactored type missing: ${move.to}`);
  }
  // Compare per-service multisets: handlers can be extracted to another controller without changing routes.
  for (const module of modules) {
    const expected = baseline.routes.filter(c => c.module === module).flatMap(c => c.annotations).sort();
    const actual = entries.filter(e => e.module === module).flatMap(e => [...e.source.matchAll(/@(?:Request|Get|Post|Put|Patch|Delete)Mapping\s*(?:\([^)]*\))?/g)].map(m => m[0].replace(/\s+/g, ' '))).sort();
    if (JSON.stringify(actual) !== JSON.stringify(expected)) errors.push(`HTTP mappings changed: ${module}`);
  }
  return { services: modules.length - 2, movedTypes: baseline.moves.length, preservedTypes: baseline.protectedTypes.length, checkedControllers: baseline.routes.length, errors };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = checkPackageLayout();
  console.log(JSON.stringify(result, null, 2));
  if (result.errors.length) process.exitCode = 1;
}
