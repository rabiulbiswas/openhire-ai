// Synthetic data only. Requires a separately started MongoDB on the dedicated rehearsal port.
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

const shell = process.env.MONGOSH_BIN || 'mongosh';
const database = `openhire_tenant_rehearsal_${randomUUID().replaceAll('-', '')}`;
const uri = `mongodb://127.0.0.1:27939/${database}?directConnection=true`;
const run = (args, extra = {}) => execFileSync(shell, [uri, '--quiet', ...(args[0] === '--eval' ? ['--eval', `(async () => { ${args[1]} })()`] : args)], {
  encoding: 'utf8', timeout: 60_000, stdio: ['ignore', 'pipe', 'pipe'],
  env: { ...process.env, OPENHIRE_TENANT_MIGRATION_DB: database,
    OPENHIRE_TENANT_MIGRATION_APPLY: 'false', OPENHIRE_TENANT_MIGRATION_MAINTENANCE: 'false', ...extra },
});
const migration = ['--file', 'scripts/maintenance/migrate-tenant-fields.mongosh.js'];
run(['--eval', `
  if ((await db.getCollectionNames()).length) throw Error('Rehearsal database must be empty');
  await db.organizations.insertMany([
    {_id:'tenant-a',slug:'alpha',name:'Alpha',_class:'com.openhire.identity.domain.IdentityModels$Organization'},
    {_id:'tenant-b',slug:'beta',name:'Beta',_class:'com.openhire.identity.domain.IdentityModels$Organization'}]);
  await db.organizations.createIndex({slug:1},{unique:true});
  for (const name of ['users','roles']) {
    const field=name==='users'?'email':'name';
    await db.getCollection(name).createIndex({organizationId:1,[field]:1},{unique:true});
    await db.getCollection(name).insertMany(['tenant-a','tenant-b'].map((id,i)=>({
      _id:name+i,organizationId:id,[field]:name==='users'?'same@example.invalid':'RECRUITER',
      roles:['RECRUITER'],createdAt:new Date('2026-01-01')})));
  }
  await db.api_keys.insertOne({_id:'key',organizationId:'tenant-a',secretHash:'synthetic-key-hash'});
  await db.invites.insertOne({_id:'invite',organizationId:'tenant-b',tokenHash:'synthetic-invite-hash'});
  await db.untouched.insertOne({_id:'external',organizationId:'leave-alone'});
`]);
assert.match(run(migration), /"mode":"dry-run"/);
assert.throws(() => run(migration, { OPENHIRE_TENANT_MIGRATION_DB: 'wrong_database' }), /Wrong database/);
assert.throws(() => run(migration, { OPENHIRE_TENANT_MIGRATION_APPLY: 'true' }), /stopped application writers/);
run(['--eval', `if (!await db.users.findOne({organizationId:'tenant-a'}) || await db.users.findOne({tenantId:'tenant-a'})) throw Error('Dry run wrote data');`]);
run(migration, { OPENHIRE_TENANT_MIGRATION_APPLY: 'true', OPENHIRE_TENANT_MIGRATION_MAINTENANCE: 'true' });
run(['--eval', `
  const check=(ok,message)=>{if(!ok)throw Error(message)};
  check(!((await db.getCollectionNames()).includes('organizations')),'Old registry still present');
  check((await db.tenants.findOne({_id:'tenant-a'})).slug==='alpha','Tenant ID changed');
  check((await db.tenants.findOne({_id:'tenant-a'}))._class.endsWith('$Tenant'),'Class not migrated');
  check(await db.users.countDocuments({email:'same@example.invalid'})===2,'Cross-tenant email lost');
  check(await db.roles.countDocuments({name:'RECRUITER'})===2,'Cross-tenant role lost');
  check(await db.users.countDocuments({organizationId:{$exists:true}})===0,'Legacy field remains');
  check((await db.api_keys.findOne({_id:'key'})).secretHash==='synthetic-key-hash','Key changed');
  check((await db.invites.findOne({_id:'invite'})).tokenHash==='synthetic-invite-hash','Invite changed');
  const user=await db.users.findOne({_id:'users0'});
  check(user.tenantId==='tenant-a' && user.createdAt.toISOString()==='2026-01-01T00:00:00.000Z','Ownership/date changed');
  check((await db.users.getIndexes()).some(i=>i.unique&&i.key.tenantId===1&&i.key.email===1),'Unique index lost');
  let duplicate=false;
  try{await db.users.insertOne({_id:'duplicate',tenantId:'tenant-a',email:'same@example.invalid'})}catch(e){duplicate=e.code===11000}
  check(duplicate,'Same-tenant duplicate was accepted');
  check((await db.untouched.findOne({_id:'external'})).organizationId==='leave-alone','Unknown collection changed');
  check(await db.getCollection('__tenant_rename_backup_v1_users').countDocuments({organizationId:{$exists:true}})===2,'Backup missing');
`]);
run(migration, { OPENHIRE_TENANT_MIGRATION_APPLY: 'true', OPENHIRE_TENANT_MIGRATION_MAINTENANCE: 'true' });
assert.match(run(migration), /"changes":0/);
// Conflicting fields must stop preflight without touching other records.
run(['--eval', `await db.users.insertOne({_id:'conflict',organizationId:'tenant-a',tenantId:'tenant-b',email:'conflict@example.invalid'});`]);
assert.throws(() => run(migration, { OPENHIRE_TENANT_MIGRATION_APPLY: 'true', OPENHIRE_TENANT_MIGRATION_MAINTENANCE: 'true' }), /Conflicting/);
run(['--eval', `if ((await db.users.findOne({_id:'conflict'})).organizationId!=='tenant-a') throw Error('Conflict was modified');`]);
console.log(`PASS: isolated rehearsal (${database}); dry-run, apply, retry, indexes, IDs, hashes and conflict rejection verified. No live database connection used.`);
