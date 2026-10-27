import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';

assert(process.argv.length === 3 && process.argv[2] === '--apply', 'Usage: node scripts/setup/create-candidate-demo-logins.mjs --apply');
const slug = 'codelogicx';
function mongo(script) {
  let result;
  try { result = execFileSync('docker', ['run', '--rm', '--network', 'openhire_default', 'mongo:7.0.41-jammy', 'mongosh', 'mongodb://host.docker.internal:27017/openhire_identity', '--quiet', '--eval', script], { encoding: 'utf8', timeout: 45000 }); }
  catch (error) { if (error.code === 'ETIMEDOUT' && error.stdout?.trim()) result = error.stdout; else throw new Error('Local membership lookup failed; no existing account was reset'); }
  return JSON.parse(result.trim());
}
const tenant = mongo('print(JSON.stringify(db.organizations.findOne({slug:"codelogicx"},{name:1,status:1})))');
assert(tenant?.status === 'ACTIVE', 'Existing active CodeLogicx tenant required');
const seed = mongo('print(JSON.stringify(db.demo_seed_runs.findOne({_id:"codelogicx-demo-v1"},{tenantId:1,"selfServiceRefresh.candidates.email":1,"selfServiceRefresh.candidates.name":1})))');
assert(seed?.tenantId === tenant._id && seed.selfServiceRefresh?.candidates?.length === 2, 'Existing demo candidate fixtures required');
execFileSync('docker', ['exec', 'openhire-keycloak-1', 'sh', '-c', '/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/openhire-candidate-seed-admin.config --server http://localhost:8080/auth --realm master --user "${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null'], { stdio: ['ignore', 'ignore', 'pipe'], timeout: 30000 });
function kc(args, body) { return execFileSync('docker', ['exec', ...(body ? ['-i'] : []), 'openhire-keycloak-1', '/opt/keycloak/bin/kcadm.sh', ...args, '--config', '/tmp/openhire-candidate-seed-admin.config'], { encoding: 'utf8', input: body && JSON.stringify(body), timeout: 30000 }).trim(); }
const realm = 'openhire-staff';
const roles = JSON.parse(kc(['get', 'roles', '-r', realm, '--fields', 'name']));
if (!roles.some(r => r.name === 'CANDIDATE')) kc(['create', 'roles', '-r', realm, '-f', '-'], { name: 'CANDIDATE', description: 'Own candidate applications only; no staff access' });
for (const candidate of seed.selfServiceRefresh.candidates) {
  assert(/^demo\.portal-[12]@codelogicx\.example\.test$/.test(candidate.email), 'Only existing synthetic demo candidates may be provisioned');
  let users = JSON.parse(kc(['get', 'users', '-r', realm, '-q', `username=${candidate.email}`, '-q', 'exact=true']));
  assert(users.length <= 1, 'Ambiguous existing account');
  const created = !users.length;
  if (created) {
    const password = `OhCand!${randomBytes(18).toString('base64url')}9aA`;
    const id = kc(['create', 'users', '-r', realm, '-f', '-', '-i'], { username: candidate.email, email: candidate.email, firstName: 'Demo', lastName: candidate.name.replace(/^CodeLogicx Demo /, ''), enabled: true, emailVerified: true, requiredActions: [], attributes: { tenant_id: [tenant._id], organization_id: [tenant._id], openhire_seed: ['codelogicx-candidate-login-v1'] }, credentials: [{ type: 'password', value: password, temporary: false }] });
    assert(/^[0-9a-f-]{36}$/i.test(id), 'Unexpected new account ID');
    kc(['add-roles', '-r', realm, '--uid', id, '--rolename', 'CANDIDATE']);
    console.log(JSON.stringify({ newDemoCredential: true, username: candidate.email, password }));
    users = JSON.parse(kc(['get', 'users', '-r', realm, '-q', `username=${candidate.email}`, '-q', 'exact=true']));
  }
  const user = users[0];
  assert(user.enabled && user.emailVerified && user.email === candidate.email && (user.attributes?.tenant_id?.[0] || user.attributes?.organization_id?.[0]) === tenant._id, 'Existing account differs; not changed');
  const mapped = JSON.parse(kc(['get', `users/${user.id}/role-mappings/realm`, '-r', realm]));
  const business = ['CANDIDATE', 'SUPER_ADMIN', 'ORG_ADMIN', 'RECRUITER', 'HR_ADMIN', 'HIRING_MANAGER', 'EMPLOYEE', 'INTERVIEWER', 'AGENCY_RECRUITER', 'COMPLIANCE_OFFICER'];
  assert.deepEqual(mapped.filter(r => business.includes(r.name)).map(r => r.name), ['CANDIDATE'], 'Unexpected existing privileges; not changed');
  const member = { _id: user.id, organizationId: tenant._id, email: candidate.email, displayName: candidate.name, status: 'ACTIVE', roles: ['CANDIDATE'], createdAt: new Date().toISOString(), _class: 'com.openhire.identity.domain.IdentityModels$Member', seedTag: 'codelogicx-candidate-login-v1' };
  mongo(`const m=${JSON.stringify(member)};const old=db.users.findOne({_id:m._id});if(old&&(old.organizationId!==m.organizationId||old.email!==m.email||old.roles.length!==1||old.roles[0]!=="CANDIDATE"))throw Error("Existing membership differs");m.createdAt=new Date(m.createdAt);db.users.updateOne({_id:m._id,organizationId:m.organizationId},{$setOnInsert:m},{upsert:true});print(JSON.stringify({ok:true}))`);
  console.log(JSON.stringify({ ready: candidate.email, existingPasswordPreserved: !created }));
}
