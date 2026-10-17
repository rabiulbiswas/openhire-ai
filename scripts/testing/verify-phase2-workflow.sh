#!/usr/bin/env bash
set -Eeuo pipefail

gateway="${OPENHIRE_GATEWAY_URL:-http://localhost:8080}"
job_service="${OPENHIRE_JOB_SERVICE_URL:-http://localhost:8082}"
approval_service="${OPENHIRE_APPROVAL_SERVICE_URL:-http://localhost:8083}"
keycloak="${OPENHIRE_KEYCLOAK_URL:-http://localhost:8180}"
tenant="${OPENHIRE_TENANT_ID:-demo-org}"
actor="${OPENHIRE_ACTOR_ID:-phase2-verifier}"
client_id="${OPENHIRE_VERIFY_CLIENT_ID:-openhire-service}"
client_secret="${OPENHIRE_VERIFY_CLIENT_SECRET:-replace-before-use}"

fail() {
  printf 'FAILED: %s\n' "$1" >&2
  exit 1
}

require_ok() {
  local label="$1"
  local url="$2"
  if ! curl --fail --silent --show-error --max-time 5 "$url" >/dev/null; then
    fail "$label is unavailable at $url. Run ./run-backend.sh up and confirm ./run-backend.sh status lists keycloak, job-service and approval-service as healthy."
  fi
  printf 'PASS: %s\n' "$label"
}

json_value() {
  local expression="$1"
  python3 -c "import json,sys; print($expression)"
}

require_ok "gateway health" "$gateway/actuator/health"
require_ok "job-service health" "$job_service/actuator/health"
require_ok "approval-service health" "$approval_service/actuator/health"
require_ok "staff realm discovery" "$keycloak/realms/openhire-staff/.well-known/openid-configuration"

token_response="$(curl --fail-with-body --silent --show-error --max-time 10 \
  -X POST "$keycloak/realms/openhire-staff/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode "client_id=$client_id" \
  --data-urlencode "client_secret=$client_secret")" || fail "could not obtain the verification service token"
access_token="$(printf '%s' "$token_response" | json_value 'json.load(sys.stdin)["access_token"]')"
headers=(-H "Authorization: Bearer $access_token" -H "Content-Type: application/json" -H "X-Tenant-Id: $tenant" -H "X-Actor-Id: $actor")
printf 'PASS: verification service token\n'

if ! curl --fail-with-body --silent --show-error --max-time 10 "$gateway/api/v1/jobs" \
  -H "Authorization: Bearer $access_token" -H "X-Tenant-Id: $tenant" >/dev/null; then
  fail "the gateway does not expose /api/v1/jobs. Rebuild the api-gateway image with ./run-backend.sh up; an older Phase 1 container returns 404 here."
fi
printf 'PASS: gateway job route\n'

create='{"title":"Phase 2 verification engineer","team":"Platform","level":"L4","location":"Remote","salaryBand":{"currency":"INR","min":20,"max":30},"mustHaveSkills":["Java","Spring Boot"],"knockoutQuestions":[],"rubric":[{"id":"r1","label":"Backend engineering","weight":100}],"description":"Initial verification description","status":"DRAFT"}'
created="$(curl --fail-with-body --silent --show-error -X POST "$job_service/api/v1/jobs" "${headers[@]}" -H "Idempotency-Key: phase2-$(date +%s)" -d "$create")"
job_id="$(printf '%s' "$created" | json_value 'json.load(sys.stdin)["id"]')"
version="$(printf '%s' "$created" | json_value 'json.load(sys.stdin)["currentVersion"]["version"]')"
printf 'PASS: created draft %s (version %s)\n' "$job_id" "$version"

edit="${create/Initial verification description/Edited verification description}"
edited="$(curl --fail-with-body --silent --show-error -X PATCH "$job_service/api/v1/jobs/$job_id" "${headers[@]}" -H "If-Match: $version" -d "$edit")"
version="$(printf '%s' "$edited" | json_value 'json.load(sys.stdin)["currentVersion"]["version"]')"
printf 'PASS: edited immutable version %s\n' "$version"

curl --fail-with-body --silent --show-error -X POST "$job_service/api/v1/jobs/$job_id/submit" "${headers[@]}" >/dev/null
printf 'PASS: submitted for approval\n'

approvals="$(curl --fail-with-body --silent --show-error "$approval_service/api/v1/approvals?scope=waiting_on_me" \
  -H "Authorization: Bearer $access_token" -H "X-Tenant-Id: $tenant" -H "X-Actor-Id: $actor")"
approval_id="$(printf '%s' "$approvals" | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; print(next(x["id"] for x in d if x.get("title")=="Phase 2 verification engineer"))')"
curl --fail-with-body --silent --show-error -X POST "$approval_service/api/v1/approvals/$approval_id/approve" "${headers[@]}" >/dev/null
printf 'PASS: approved through approval-service\n'

curl --fail-with-body --silent --show-error -X POST "$job_service/api/v1/jobs/$job_id/publish" "${headers[@]}" >/dev/null
published="$(curl --fail-with-body --silent --show-error "$job_service/api/v1/jobs/$job_id" \
  -H "Authorization: Bearer $access_token" -H "X-Tenant-Id: $tenant")"
status="$(printf '%s' "$published" | json_value 'json.load(sys.stdin)["status"]')"
[[ "$status" == "OPEN" ]] || fail "expected OPEN after publish, got $status"
printf 'PASS: published job is OPEN\n'
printf 'Phase 2 workflow completed: create -> edit -> submit -> approve -> publish.\n'
