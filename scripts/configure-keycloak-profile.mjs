// Idempotent local realm migration. Preserves unrelated profile and client settings; never recreates realms.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const base = process.env.OPENHIRE_KEYCLOAK_URL || 'http://localhost:8180';
assert(['localhost','127.0.0.1'].includes(new URL(base).hostname), 'This migration is restricted to local Keycloak');
const response = await fetch(`${base}/realms/master/protocol/openid-connect/token`, { method:'POST', body:new URLSearchParams({grant_type:'password',client_id:'admin-cli',username:process.env.KEYCLOAK_ADMIN_USERNAME || 'admin',password:process.env.KEYCLOAK_ADMIN_PASSWORD || 'admin'}) });
assert.equal(response.status,200,'Keycloak administration login failed');
const token=(await response.json()).access_token;
const headers={Authorization:`Bearer ${token}`,'Content-Type':'application/json'};
const realm=`${base}/admin/realms/openhire-staff`;
const current=await fetch(`${realm}/users/profile`,{headers}); assert.equal(current.status,200);
const profile=await current.json();
profile.attributes ||= [];
for(const name of ['organization_id','organization_name','openhire_scim_id']) {
  const existing=profile.attributes.find(a=>a.name===name);
  const managed={...existing,name,displayName:name,multivalued:false,permissions:{view:['admin','user'],edit:['admin']},validations:{...existing?.validations,length:{max:200}}};
  if(existing) Object.assign(existing,managed); else profile.attributes.push(managed);
}
const saved=await fetch(`${realm}/users/profile`,{method:'PUT',headers,body:JSON.stringify(profile)}); assert.equal(saved.status,200);
const clientsResponse=await fetch(`${realm}/clients?clientId=openhire-web`,{headers}); assert.equal(clientsResponse.status,200);
const [client]=await clientsResponse.json(); assert(client,'Staff client missing');
const desired=JSON.parse(readFileSync(new URL('../infra/keycloak/openhire-staff-realm.json',import.meta.url))).clients.find(c=>c.clientId==='openhire-web');
client.redirectUris=[...new Set([...(client.redirectUris||[]),...desired.redirectUris])];
client.webOrigins=[...new Set([...(client.webOrigins||[]),...desired.webOrigins])];
const savedClient=await fetch(`${realm}/clients/${client.id}`,{method:'PUT',headers,body:JSON.stringify(client)}); assert.equal(savedClient.status,204);
const scopesResponse=await fetch(`${realm}/client-scopes`,{headers}); assert.equal(scopesResponse.status,200);
const scopes=await scopesResponse.json();
const basic=scopes.find(scope=>scope.name==='basic'); assert(basic,'Standard basic OIDC scope is missing');
const basicResult=await fetch(`${realm}/clients/${client.id}/default-client-scopes/${basic.id}`,{method:'PUT',headers}); assert.equal(basicResult.status,204);
const mapperResponse=await fetch(`${realm}/clients/${client.id}/protocol-mappers/models`,{headers}); assert.equal(mapperResponse.status,200);
const mappers=await mapperResponse.json();
for(const mapper of desired.protocolMappers) {
  const existing=mappers.find(m=>m.name===mapper.name);
  const result=await fetch(`${realm}/clients/${client.id}/protocol-mappers/models${existing?`/${existing.id}`:''}`,{method:existing?'PUT':'POST',headers,body:JSON.stringify({...existing,...mapper})});
  assert([201,204].includes(result.status),`Mapper update failed: ${mapper.name}`);
}
console.log('Configured admin-managed organization attributes and staff/partner OIDC callbacks and claims; existing realm preserved.');
