'use strict';

// Pure migration rules. BSON values are retained; no JSON round-trip or new tenant IDs.
const FIELD_RENAMES = Object.freeze({
  organizationId: 'tenantId', organization_id: 'tenant_id',
  organizationName: 'tenantName', organization_name: 'tenant_name',
});
const own = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const equal = (left, right) => JSON.stringify(left) === JSON.stringify(right);
function canonicalFields(document) {
  const fields = {};
  for (const [oldName, newName] of Object.entries(FIELD_RENAMES)) {
    if (newName.endsWith('Id') || newName.endsWith('_id')) {
      for (const name of [oldName, newName]) {
        if (own(document, name) && (typeof document[name] !== 'string' || !document[name].trim() || document[name] !== document[name].trim())) {
          throw new Error('Missing or invalid tenant identifier');
        }
      }
    }
    if (!own(document, oldName)) continue;
    if (own(document, newName) && !equal(document[oldName], document[newName])) {
      throw new Error(`Conflicting ${oldName}/${newName}; manual review required`);
    }
    fields[newName] = document[oldName];
  }
  return fields;
}
function planDocument(original) {
  const canonical = canonicalFields(original);
  const document = { ...original, ...canonical };
  for (const oldName of Object.keys(FIELD_RENAMES)) delete document[oldName];
  if (document._class === 'com.openhire.identity.domain.IdentityModels$Organization') {
    document._class = 'com.openhire.identity.domain.IdentityModels$Tenant';
  }
  return { changed: !equal(original, document), document, canonical };
}
function renameKeys(value) {
  if (Array.isArray(value)) return value.map(renameKeys);
  // mongosh returns documents from another JS realm, so prototype identity is not portable.
  // Retain BSON/Date instances while recursively renaming plain index specifications.
  if (!value || Object.getPrototypeOf(value)?.constructor?.name !== 'Object') return value;
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [FIELD_RENAMES[key] || key, renameKeys(item)]));
}
function planIndex(index) {
  if (!Object.keys(index.key).some(key => own(FIELD_RENAMES, key))) return null;
  const keys = renameKeys(index.key);
  const options = { name: Object.entries(keys).map(([key, direction]) => `${key}_${direction}`).join('_') };
  if (Object.keys(keys).some(key => own(FIELD_RENAMES, key)) || options.name === index.name) {
    throw new Error('Index rename did not produce a distinct canonical index');
  }
  for (const option of ['unique', 'sparse', 'expireAfterSeconds', 'collation', 'hidden']) if (own(index, option)) options[option] = index[option];
  if (index.partialFilterExpression) options.partialFilterExpression = renameKeys(index.partialFilterExpression);
  return { oldName: index.name, keys, options };
}
const api = { FIELD_RENAMES, canonicalFields, planDocument, planIndex };
if (typeof module !== 'undefined') module.exports = api;
globalThis.openhireTenantFieldPlan = api;
