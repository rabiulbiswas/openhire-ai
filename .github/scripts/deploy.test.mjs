import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./deploy.mjs', import.meta.url));
const root = fileURLToPath(new URL('../../', import.meta.url));
const sha = 'a'.repeat(40);
const prefix = '123456789012.dkr.ecr.ap-south-2.amazonaws.com/openhire';

function simulate(failure = '') {
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'openhire-cd-test-'));
  fs.mkdirSync(path.join(temp, 'bin'));
  fs.mkdirSync(path.join(temp, 'release-input'));
  fs.symlinkSync(path.join(root, 'backend'), path.join(temp, 'backend'), 'dir');
  fs.writeFileSync(path.join(temp, 'release-input/release.json'), JSON.stringify({
    sha, runId: '123', services: ['job-service'], images: [{service:'job-service', repository:prefix+'/job-service', digest:'sha256:'+'b'.repeat(64), sha}]
  }));
  fs.writeFileSync(path.join(temp, 'values.json'), JSON.stringify({ci:{sourceSha:'c'.repeat(40)}, services:{'job-service':{enabled:true}}}));
  fs.writeFileSync(path.join(temp, 'calls'), '');
  const fake = `#!/usr/bin/env node
const fs=require('fs'),path=require('path');
const command=path.basename(process.argv[1]),args=process.argv.slice(2);
fs.appendFileSync(process.env.FAKE_CALLS,JSON.stringify({command,args})+'\\n');
if(command==='git' && args[0]==='merge-base' && process.env.FAKE_FAILURE==='stale')process.exit(1);
if(command==='git' && args[0]==='diff')process.stdout.write(process.env.FAKE_FAILURE==='missing'?'backend/identity-service/src/main/X.java\\0':'backend/job-service/src/main/X.java\\0');
if(command==='helm' && args[0]==='get')process.stdout.write(fs.readFileSync('values.json'));
if(command==='helm' && args[0]==='upgrade'){
 fs.copyFileSync(args[args.indexOf('-f')+1],'captured-overrides.json');
 if(process.env.FAKE_FAILURE==='upgrade')process.exit(1);
}
if(command==='kubectl' && args[0]==='get' && args[1]==='secret' && process.env.FAKE_FAILURE==='secret')process.exit(1);
`;
  for (const name of ['git','helm','kubectl']) fs.writeFileSync(path.join(temp,'bin',name), fake, {mode:0o700});
  try {
    const result = spawnSync(process.execPath,[script],{cwd:temp,encoding:'utf8',env:{...process.env,
      PATH:path.join(temp,'bin')+':'+process.env.PATH, FAKE_CALLS:path.join(temp,'calls'), FAKE_FAILURE:failure,
      SOURCE_SHA:sha,SOURCE_RUN:'123',HELM_RELEASE:'openhire',K8S_NAMESPACE:'testing',ECR_PREFIX:prefix,
      DEPLOY_ENVIRONMENT:'testing',GITHUB_STEP_SUMMARY:path.join(temp,'summary')
    }});
    return {...result,calls:fs.readFileSync(path.join(temp,'calls'),'utf8').trim().split('\n').filter(Boolean).map(s=>JSON.parse(s)),
      overrides:fs.existsSync(path.join(temp,'captured-overrides.json'))?JSON.parse(fs.readFileSync(path.join(temp,'captured-overrides.json'),'utf8')):null,
      summary:fs.existsSync(path.join(temp,'summary'))?fs.readFileSync(path.join(temp,'summary'),'utf8'):''};
  } finally { fs.rmSync(temp,{recursive:true,force:true}); }
}

test('deployment entrypoint upgrades only approved images and reports live rollout status',()=>{
  const result=simulate();assert.equal(result.status,0,result.stderr);
  const upgrade=result.calls.find(c=>c.command==='helm'&&c.args[0]==='upgrade');
  assert.ok(upgrade.args.includes('--reuse-values'));assert.ok(upgrade.args.includes('--atomic'));
  assert.deepEqual(Object.keys(result.overrides.services),['job-service']);
  assert.ok(result.calls.some(c=>c.command==='kubectl'&&c.args[0]==='rollout'));
  assert.match(result.summary,/Rollout completed/);
});
test('missing images, stale source or absent runtime secrets prevent upgrade',()=>{
  for(const failure of ['missing','stale','secret']){
    const result=simulate(failure);assert.notEqual(result.status,0,failure);
    assert.ok(!result.calls.some(c=>c.command==='helm'&&c.args[0]==='upgrade'),failure);
  }
});
test('failed upgrade reports failure and pod status, never success or application secrets',()=>{
  const result=simulate('upgrade');assert.notEqual(result.status,0);
  assert.match(result.summary,/Deployment failed/);assert.doesNotMatch(result.summary,/Rollout completed/);
  assert.ok(result.calls.some(c=>c.command==='kubectl'&&c.args[1]==='pods'));
  assert.ok(!result.calls.some(c=>c.command==='kubectl'&&c.args[0]==='logs'));
});
