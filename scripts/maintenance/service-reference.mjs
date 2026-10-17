// Read-only documentation extractor. --patch emits an apply_patch patch; --check detects stale references.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const backend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const modules = [...fs.readFileSync(path.join(backend, 'settings.gradle'), 'utf8').matchAll(/include '([^']+)'/g)]
  .map(m => m[1]).filter(m => !['platform-starter', 'service-support'].includes(m));
function files(dir) { return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? files(path.join(dir, e.name)) : [path.join(dir, e.name)]); }
function close(s, at, left = '(', right = ')') {
  let depth = 0, quote = null;
  for (let i = at; i < s.length; i++) {
    const c = s[i];
    if (quote) { if (c === '\\') i++; else if (c === quote) quote = null; continue; }
    if (c === '"' || c === "'") { quote = c; continue; }
    if (c === '/' && s[i + 1] === '/') { i = s.indexOf('\n', i); if (i < 0) break; continue; }
    if (c === '/' && s[i + 1] === '*') { i = s.indexOf('*/', i + 2) + 1; continue; }
    if (c === left) depth++;
    if (c === right && --depth === 0) return i;
  }
  throw new Error(`Unbalanced source at ${at}`);
}
function annotation(s, at) {
  const match = /^@([\w.]+)/.exec(s.slice(at)); if (!match) throw new Error('Annotation expected');
  let end = at + match[0].length; while (/\s/.test(s[end] || '') && s[end]) end++;
  if (s[end] === '(') end = close(s, end) + 1;
  return { name: match[1], text: s.slice(at, end).trim(), end };
}
function routes(text) {
  const args = text.slice(text.indexOf('(') + 1, text.lastIndexOf(')'));
  if (!text.includes('(')) return [''];
  const selected = args.match(/(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")/)?.[1]
    ?? (/^\s*["{]/.test(args) ? args : '');
  return [...selected.matchAll(/"([^"]*)"/g)].map(m => m[1]).filter(p => !p.includes('application/'));
}
const compact = s => s.replace(/\s+/g, ' ').trim();
function render(module) {
  const sources = files(path.join(backend, module, 'src/main/java')).filter(f => f.endsWith('.java')).map(file => ({file, text: fs.readFileSync(file, 'utf8')}));
  const records = [], endpoints = [], controllers = [];
  for (const source of sources) {
    const { file, text } = source;
    const relative = path.relative(path.join(backend, 'docs/services'), file).split(path.sep).join('/');
    for (const match of text.matchAll(/\brecord\s+(\w+)(?:<[^\n{]+?>)?\s*\(/g)) {
      const start = match.index, paren = text.indexOf('(', start), end = close(text, paren);
      records.push({ name: match[1], declaration: text.slice(start, end + 1), relative });
    }
    if (!text.includes('@RestController')) continue;
    const classAt = text.search(/\bclass\s+\w+/);
    const prefixText = text.slice(0, classAt);
    const prefixAt = prefixText.indexOf('@RequestMapping');
    const bases = prefixAt < 0 ? [''] : routes(annotation(text, prefixAt).text);
    const className = text.slice(classAt).match(/class\s+(\w+)/)[1];
    const entries = [];
    for (const match of text.matchAll(/@(Get|Post|Put|Patch|Delete|Head|Options)Mapping\b/g)) {
      const mapping = annotation(text, match.index);
      let at = mapping.end; const extra = [];
      while (true) {
        while (/\s/.test(text[at] || '') && text[at]) at++;
        if (text[at] !== '@') break;
        const next = annotation(text, at); extra.push(next.text); at = next.end;
      }
      const paren = text.indexOf('(', at), endParams = close(text, paren);
      const brace = text.indexOf('{', endParams), endBody = close(text, brace, '{', '}');
      const signature = compact(text.slice(at, endParams + 1));
      const body = text.slice(brace + 1, endBody);
      const method = text.slice(at, paren).trim().match(/(\w+)$/)?.[1];
      if (!method) throw new Error(`Method not found in ${file}`);
      const line = text.slice(0, match.index).split('\n').length;
      const checks = [...body.matchAll(/(?:viewers|access|policy)\.[\w]+\([^;\n]*?\)|\brequire\(auth,\s*\w+\)|\btenant\(auth\)/g)].map(m => compact(m[0]));
      const authorization = extra.filter(a => a.startsWith('@PreAuthorize'));
      const delegated = [...body.matchAll(/(?:return\s+)?(?:interviews|offers|employees|sagas|tasks|settings|prompts|evaluation|ops|dispatch|communication|rediscovery|search|invitations|consent|integrity|service|queue|calendars|connections|confirmations|users|directory)\.([\w]+)\(/g)].map(m => m[0].replace(/^return\s+/, '').replace('(', ''));
      const status = extra.find(a => a.startsWith('@ResponseStatus')) ?? (signature.includes('ResponseEntity') || body.includes('ResponseEntity.') ? 'ResponseEntity chooses status; see handler' : '200 (normal return; exceptions may differ)');
      for (const base of bases.length ? bases : ['']) for (const route of routes(mapping.text).length ? routes(mapping.text) : ['']) {
        const full = base + route;
        entries.push({verb:match[1].toUpperCase(), route:full || '/', signature, method, line, relative, status, controls:[...new Set([...authorization,...checks])], delegated:[...new Set(delegated)]});
      }
    }
    if (entries.length) { controllers.push({className,relative,entries}); endpoints.push(...entries); }
  }
  let output = `# ${module} — REST endpoint reference\n\nGenerated from current Java controller annotations. This is a source-level reference, not proof of deployed reachability or live acceptance. See [service design](${module}.md) for business rules and end-to-end flows, and [shared conventions](END_TO_END_DESIGN.md#api-and-security-conventions).\n\n`;
  output += `Mapped operations: **${endpoints.length}**. Framework-generated Actuator endpoints and gateway-proxied routes are not duplicated here. Signatures preserve request headers, query defaults, body types, return types and optional bindings. Java types are not JSON field names: request/response record fields are listed below. Controller checks may delegate additional authorization to application services; an empty extracted-check list does not mean public access.\n\n`;
  for (const c of controllers) {
    output += `## ${c.className}\n\nSource: [${c.className}](${c.relative}).\n\n`;
    for (const e of c.entries) {
      output += `### ${e.verb} \`${e.route}\`\n\n- Handler: \`${e.method}\` ([source](${e.relative}#L${e.line})).\n- Success status: ${e.status.replaceAll('@','\\@')}.\n- Controller access/tenant calls: ${e.controls.length ? e.controls.map(x=>'`'+x+'`').join('; ') : 'See handler and service authorization; no simple access helper extracted'}.\n`;
      if(e.delegated.length) output += `- Delegated operations: ${e.delegated.map(x=>'`'+x+'`').join(', ')}.\n`;
      output += `\nRequest bindings and declared response:\n\n\`\`\`java\n${e.signature}\n\`\`\`\n\n`;
    }
  }
  const selected = new Set(); let vocabulary = endpoints.map(e=>e.signature).join(' ');
  let changed = true;
  while(changed) { changed=false; for (const r of records) if(!selected.has(r) && new RegExp(`\\b${r.name}\\b`).test(vocabulary)) { selected.add(r); vocabulary+=' '+r.declaration; changed=true; } }
  output += '## Request and response record fields\n\nThese are source declarations, including nested DTO records referenced by signatures. Same-named records in different files are scoped to their linked owner. Constructor/domain validation and enum values remain in the linked source; `Map` responses are deliberately not assigned an invented schema.\n\n';
  for (const r of selected) output += `### ${r.name}\n\n[Definition](${r.relative})\n\n\`\`\`java\n${r.declaration}\n\`\`\`\n\n`;
  if(!selected.size) output += 'No matching record DTOs; follow the handler/source links for class- or map-based payloads.\n';
  return {file:path.join(backend,'docs/services',`${module}-endpoints.md`),content:output.trimEnd()+'\n',count:endpoints.length};
}
const requested = process.argv.find(arg => arg.startsWith('--service='))?.split('=')[1];
if (requested && !modules.includes(requested)) throw new Error('Unknown service');
const documents = (requested ? [requested] : modules).map(render);
if(process.argv.includes('--patch')) {
  let patch='*** Begin Patch\n';
  const patchLines = (content, prefix) => content.replace(/\n$/, '').split('\n').map(line => prefix + line).join('\n') + '\n';
  for (const d of documents) {
    if (fs.existsSync(d.file)) {
      const current = fs.readFileSync(d.file, 'utf8');
      if (current === d.content) continue;
      patch += `*** Update File: ${d.file}\n@@\n` + patchLines(current, '-') + patchLines(d.content, '+');
    } else {
      patch += `*** Add File: ${d.file}\n` + patchLines(d.content, '+');
    }
  }
  console.log(patch+'*** End Patch');
} else if(process.argv.includes('--check')) {
  const stale=documents.filter(d=>!fs.existsSync(d.file)||fs.readFileSync(d.file,'utf8')!==d.content);
  console.log(JSON.stringify({services:documents.length,operations:documents.reduce((n,d)=>n+d.count,0),stale:stale.map(d=>path.basename(d.file))}));
  if(stale.length)process.exitCode=1;
} else console.log(JSON.stringify(documents.map(d=>({file:d.file,operations:d.count})),null,2));
