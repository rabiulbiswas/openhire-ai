#!/usr/bin/env bash
set -Eeuo pipefail

gateway="${OPENHIRE_GATEWAY_URL:-http://localhost:8080}"
keycloak="${OPENHIRE_KEYCLOAK_URL:-http://localhost:8180}"

require_ok() {
  local label="$1"
  local url="$2"
  if ! curl --fail --silent --show-error "$url" >/dev/null; then
    printf 'FAILED: %s (%s)\n' "$label" "$url" >&2
    printf 'Run ./run-backend.sh up, then confirm ./run-backend.sh status lists keycloak and every backend service as healthy.\n' >&2
    exit 1
  fi
  printf 'PASS: %s\n' "$label"
}

require_ok "gateway health" "$gateway/actuator/health"
require_ok "staff realm discovery" "$keycloak/realms/openhire-staff/.well-known/openid-configuration"
require_ok "candidate realm discovery" "$keycloak/realms/openhire-candidate/.well-known/openid-configuration"

session="$(curl --fail --silent --show-error "$gateway/bff/session")"
if [[ "$session" != *'"authenticated":false'* && "$session" != *'"authenticated":true'* ]]; then
  printf 'FAILED: BFF session contract returned an unexpected body: %s\n' "$session" >&2
  exit 1
fi
printf 'PASS: BFF session contract\n'

login_status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$gateway/oauth2/authorization/openhire-staff")"
if [[ "$login_status" != "302" ]]; then
  printf 'FAILED: staff OIDC entry point returned %s instead of 302\n' "$login_status" >&2
  exit 1
fi
printf 'PASS: OIDC authorization redirect\n'

printf 'Phase 1 runtime service checks passed. Browser interaction still validates TOTP, reset, verification and logout UX.\n'
