#!/usr/bin/env bash
set -Eeuo pipefail

: "${OPENHIRE_JOB_ID:?Set OPENHIRE_JOB_ID to an OPEN job in the development tenant}"
: "${OPENHIRE_TENANT_ID:?Set OPENHIRE_TENANT_ID}"
: "${OPENHIRE_ACCESS_TOKEN:?Set a valid staff JWT with recruiter/admin role}"
command -v python3 >/dev/null
gateway="${OPENHIRE_GATEWAY_URL:-http://localhost:8080}"
headers=(-H "Authorization: Bearer $OPENHIRE_ACCESS_TOKEN" -H "X-Tenant-Id: $OPENHIRE_TENANT_ID" -H 'Content-Type: application/json')
# Direct service verification avoids the browser BFF's CSRF requirement for cookie-based sessions.
service="${OPENHIRE_APPLICATION_SERVICE_URL:-http://localhost:8084}"
key="phase3-$(date +%s)-${RANDOM}"
payload="$(python3 -c 'import os,json; print(json.dumps({"jobId":os.environ["OPENHIRE_JOB_ID"],"name":"Phase 3 verification","email":"phase3@example.test","owner":"","processingConsent":True,"consentVersion":"development-v1","externalReference":"manual-verification"}))')"
json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }

curl --fail --silent --show-error --max-time 10 "$service/actuator/health" >/dev/null
first="$(curl --fail-with-body --silent --show-error --max-time 20 "$service/api/v1/intake/applications" "${headers[@]}" -H "Idempotency-Key: $key" -d "$payload")"
id="$(printf '%s' "$first" | json_field id)"
second="$(curl --fail-with-body --silent --show-error --max-time 20 "$service/api/v1/intake/applications" "${headers[@]}" -H "Idempotency-Key: $key" -d "$payload")"
[[ "$(printf '%s' "$second" | json_field id)" == "$id" ]]
printf 'PASS: intake and idempotent replay (%s)\n' "$id"

# The outbox acknowledgement also updates the optimistic revision; reload on a racing acknowledgement.
completed=false
for attempt in 1 2 3; do
  current="$(curl --fail --silent --show-error --max-time 10 "$service/api/v1/intake/applications/$id" "${headers[@]}")"
  revision="$(printf '%s' "$current" | json_field revision)"
  status="$(curl --silent --show-error --max-time 10 --output /dev/null --write-out '%{http_code}' \
    -X PATCH "$service/api/v1/intake/applications/$id/stage" "${headers[@]}" -H "If-Match: $revision" \
    -d '{"stage":"SHORTLISTED","reason":"Manual Phase 3 verification"}')"
  if [[ "$status" == 200 ]]; then completed=true; break; fi
  [[ "$status" == 412 ]] || { printf 'FAILED: stage update HTTP %s\n' "$status" >&2; exit 1; }
done
[[ "$completed" == true ]] || { printf 'FAILED: repeated revision conflicts\n' >&2; exit 1; }
final="$(curl --fail --silent --show-error --max-time 10 "$service/api/v1/intake/applications/$id" "${headers[@]}")"
[[ "$(printf '%s' "$final" | json_field stage)" == SHORTLISTED ]]
printf 'PASS: persisted shortlist decision\n'
curl --fail --silent --show-error --max-time 10 "$gateway/api/v1/intake/applications/$id" "${headers[@]}" >/dev/null
printf 'PASS: gateway read route\n'
printf 'Staff intake milestone verified. Resume, candidate and complete Phase 3 acceptance remain pending.\n'
