// Run from the backend root using mongosh connected to openhire_identity. Default is read-only.
// Apply only during maintenance, after an independent backup and a successful dry run.
(async () => {
await load(process.cwd() + '/scripts/maintenance/tenant-field-plan.cjs');
const rules = globalThis.openhireTenantFieldPlan;
const applyMigration = process.env.OPENHIRE_TENANT_MIGRATION_APPLY === 'true';
const expectedDatabase = process.env.OPENHIRE_TENANT_MIGRATION_DB || 'openhire_identity';
if (db.getName() !== expectedDatabase) throw new Error('Wrong database; no migration performed');
if (applyMigration && process.env.OPENHIRE_TENANT_MIGRATION_MAINTENANCE !== 'true') {
  throw new Error('Apply requires stopped application writers and OPENHIRE_TENANT_MIGRATION_MAINTENANCE=true');
}
const names = await db.getCollectionNames();
if (names.includes('organizations') && names.includes('tenants')) {
  throw new Error('Both organizations and tenants collections exist; resolve explicitly before migrating');
}
const identityCollections = ['organizations', 'tenants', 'users', 'roles', 'invites', 'api_keys', 'setup_progress', 'tenant_security_settings', 'break_glass_requests'];
const collections = identityCollections.filter(name => names.includes(name));
const plans = [];
// Preflight every document and index before any writes. Never print user records or credential material.
for (const name of collections) {
  const collection = db.getCollection(name);
  let documents = 0, changes = 0;
  for await (const document of collection.find({})) { documents++; if (rules.planDocument(document).changed) changes++; }
  const indexes = await collection.getIndexes();
  const indexesToChange = indexes.map(rules.planIndex).filter(Boolean);
  for (const planned of indexesToChange) {
    const collision = indexes.find(index => index.name === planned.options.name);
    if (collision && JSON.stringify(collision.key) !== JSON.stringify(planned.keys)) throw new Error('Index name conflict in ' + name);
    if (collision && (Boolean(collision.unique) !== Boolean(planned.options.unique)
        || Boolean(collision.sparse) !== Boolean(planned.options.sparse)
        || JSON.stringify(collision.partialFilterExpression) !== JSON.stringify(planned.options.partialFilterExpression))) {
      throw new Error('Index option conflict in ' + name);
    }
  }
  plans.push({ name, documents, changes, indexes, indexesToChange });
}
print(JSON.stringify({ mode: applyMigration ? 'apply' : 'dry-run', database: db.getName(), collections: plans.map(p => ({ name: p.name, documents: p.documents, changes: p.changes, indexes: p.indexesToChange.length })), renameCollection: names.includes('organizations') ? 'organizations -> tenants' : null }));
if (applyMigration) {
  // Complete snapshots first, including index manifests. Keep them after cutover for recovery.
  for (const plan of plans) {
    const backup = db.getCollection('__tenant_rename_backup_v1_' + plan.name);
    for await (const original of db.getCollection(plan.name).find({})) {
      await backup.updateOne({ _id: original._id }, { $setOnInsert: original }, { upsert: true });
    }
    await db.getCollection('__tenant_rename_manifest_v1').updateOne({ _id: plan.name }, { $setOnInsert: { indexes: plan.indexes, createdAt: new Date() } }, { upsert: true });
  }
  for (const plan of plans) {
    const collection = db.getCollection(plan.name);
    // Populate new fields while old unique indexes still protect the data.
    for await (const original of collection.find({})) {
      const canonical = rules.canonicalFields(original);
      if (Object.keys(canonical).length) await collection.updateOne({ _id: original._id }, { $set: canonical });
    }
    // New indexes must succeed before legacy ones are removed. A failed build leaves legacy fields intact.
    for (const index of plan.indexesToChange) await collection.createIndex(index.keys, index.options);
    for (const index of plan.indexesToChange) await collection.dropIndex(index.oldName);
    for await (const original of collection.find({})) {
      const next = rules.planDocument(original);
      if (next.changed) await collection.replaceOne({ _id: original._id }, next.document);
    }
  }
  if (names.includes('organizations')) await db.getCollection('organizations').renameCollection('tenants', false);
  print('Migration finished. IDs were preserved. Keep backup collections and the index manifest until acceptance.');
}
})()
