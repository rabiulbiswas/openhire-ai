// Local acceptance helper: real authorization-code + PKCE flow; never disables MFA or password-grant policy.
import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';

export async function staffToken() {
  if (process.env.OPENHIRE_STAFF_TOKEN) return process.env.OPENHIRE_STAFF_TOKEN;
  const username = process.env.OPENHIRE_TEST_USERNAME;
  const password = process.env.OPENHIRE_TEST_PASSWORD;
  assert(username && password, 'Provide OPENHIRE_STAFF_TOKEN or OPENHIRE_TEST_USERNAME and OPENHIRE_TEST_PASSWORD for a local non-MFA fixture account');
  const base = `${process.env.OPENHIRE_KEYCLOAK_URL || 'http://localhost:8180'}/realms/openhire-staff/protocol/openid-connect`;
  const clientId = process.env.STAFF_OIDC_CLIENT_ID || 'openhire-web';
  const redirect = 'http://localhost:8080/login/oauth2/code/openhire-staff';
  const verifier = randomBytes(48).toString('base64url');
  const state = randomBytes(24).toString('hex');
  const params = new URLSearchParams({ client_id: clientId, redirect_uri: redirect, response_type: 'code', scope: 'openid profile email', state, code_challenge: createHash('sha256').update(verifier).digest('base64url'), code_challenge_method: 'S256' });
  const login = await fetch(`${base}/auth?${params}`, { redirect: 'manual', signal: AbortSignal.timeout(15000) });
  const html = await login.text();
  const action = html.match(/<form[^>]+action="([^"]+)"/i)?.[1]?.replaceAll('&amp;', '&');
  assert(action, 'OIDC login form unavailable; use OPENHIRE_STAFF_TOKEN if this realm requires an interactive login');
  assert.equal(new URL(action).origin, new URL(base).origin, 'Unexpected login origin');
  const cookie = login.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
  const signedIn = await fetch(action, { method: 'POST', redirect: 'manual', headers: { Cookie: cookie }, body: new URLSearchParams({ username, password, credentialId: '' }), signal: AbortSignal.timeout(15000) });
  assert.equal(signedIn.status, 302, 'Login requires additional steps or credentials were rejected; use an existing staff token');
  const location = new URL(signedIn.headers.get('location'));
  assert.equal(location.searchParams.get('state'), state, 'OIDC state mismatch');
  const code = location.searchParams.get('code');
  assert(code, 'OIDC code missing');
  const response = await fetch(`${base}/token`, { method: 'POST', body: new URLSearchParams({ grant_type: 'authorization_code', code, client_id: clientId, client_secret: process.env.STAFF_OIDC_CLIENT_SECRET || 'replace-before-use', redirect_uri: redirect, code_verifier: verifier }), signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, 200, 'OIDC code exchange failed');
  return (await response.json()).access_token;
}
