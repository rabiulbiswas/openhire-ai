import test from 'node:test';
import assert from 'node:assert/strict';
import migration from '../maintenance/tenant-field-plan.cjs';
import { runInNewContext } from 'node:vm';

test('index documents from the mongosh JS realm are renamed, not dropped unchanged', () => {
  const foreign = runInNewContext('({name:"organizationId_1_email_1",key:{organizationId:1,email:1},unique:true,partialFilterExpression:{organizationId:{$exists:true}}})');
  const plan = migration.planIndex(foreign);
  assert.equal(plan.options.name, 'tenantId_1_email_1');
  assert.deepEqual(plan.keys, { tenantId: 1, email: 1 });
  assert.deepEqual(plan.options.partialFilterExpression, { tenantId: { $exists: true } });
});

test('renames legacy fields without changing IDs, credential material or unrelated values', () => {
  const original = { _id: 'user-1', organizationId: 'tenant-uuid', organizationName: 'Codlogicx', passwordHash: 'unchanged', createdAt: new Date('2026-01-01') };
  const { document, changed } = migration.planDocument(original);
  assert.equal(changed, true); assert.equal(document.tenantId, 'tenant-uuid'); assert.equal(document._id, 'user-1');
  assert.equal(document.passwordHash, original.passwordHash); assert.equal(document.createdAt, original.createdAt);
  assert.equal(document.tenantName, 'Codlogicx'); assert.equal('organizationId' in document, false);
  assert.equal(original.organizationId, 'tenant-uuid');
});
test('fails before mutation when canonical and legacy tenant values disagree', () => {
  assert.throws(() => migration.planDocument({ organizationId: 'a', tenantId: 'b' }), /Conflicting/);
  assert.throws(() => migration.planDocument({ organizationId: '' }), /invalid/);
  assert.throws(() => migration.planDocument({ tenantId: ['a'] }), /invalid/);
  assert.throws(() => migration.planDocument({ organizationId: ' a' }), /invalid/);
});
test('migration is idempotent and updates only the known legacy tenant model discriminator', () => {
  const migrated = migration.planDocument({ _id: 'stable', _class: 'com.openhire.identity.domain.IdentityModels$Organization' }).document;
  assert.equal(migrated._class, 'com.openhire.identity.domain.IdentityModels$Tenant');
  assert.equal(migration.planDocument(migrated).changed, false);
  assert.equal(migration.planDocument({ _class: 'unrelated.Organization' }).changed, false);
});
test('new index plans preserve uniqueness, partial predicates and collation', () => {
  const index = migration.planIndex({ name: 'organizationId_1_email_1', key: { organizationId: 1, email: 1 }, unique: true, collation: { locale: 'en', strength: 2 }, partialFilterExpression: { organizationId: { $exists: true } } });
  assert.deepEqual(index.keys, { tenantId: 1, email: 1 }); assert.equal(index.options.unique, true);
  assert.deepEqual(index.options.collation, { locale: 'en', strength: 2 });
  assert.deepEqual(index.options.partialFilterExpression, { tenantId: { $exists: true } });
  assert.equal(migration.planIndex({ name: 'email_1', key: { email: 1 }, unique: true }), null);
});
