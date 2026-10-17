#!/usr/bin/env bash
set -Eeuo pipefail
# Creates one real career application and withdraws it. Use a development job only.
: "${OPENHIRE_JOB_ID:?Set an OPEN development job ID}"
: "${OPENHIRE_TENANT_ID:?Set the development tenant ID}"
: "${OPENHIRE_RESUME:?Set the path to a harmless, text-containing PDF/DOC/DOCX fixture}"
command -v python3 >/dev/null
[[ -f "$OPENHIRE_RESUME" ]] || { printf 'Resume fixture is missing\n' >&2; exit 1; }
gateway="${OPENHIRE_GATEWAY_URL:-http://localhost:8080}"
field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
policy="$(curl --fail --silent --show-error --max-time 15 "$gateway/api/v1/public/consent" | field version)"
key="$(python3 -c 'import uuid; print(uuid.uuid4())')"
receipt="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"
answers="${OPENHIRE_ANSWERS:-}"
[[ -n "$answers" ]] || answers='{}'
submit() {
  curl --fail-with-body --silent --show-error --max-time 40 "$gateway/api/v1/public/applications" \
    -H "Idempotency-Key: $key" \
    --form-string "tenantId=$OPENHIRE_TENANT_ID" --form-string "jobId=$OPENHIRE_JOB_ID" \
    --form-string 'name=Phase 3 workflow fixture' --form-string "email=phase3-$key@example.test" \
    --form-string 'processingConsent=true' --form-string "consentVersion=$policy" \
    --form-string "receiptToken=$receipt" --form-string "answers=$answers" -F "resume=@$OPENHIRE_RESUME"
}
first="$(submit)"; id="$(printf '%s' "$first" | field id)"
[[ "$(submit | field id)" == "$id" ]] || { printf 'Idempotent replay failed\n' >&2; exit 1; }
printf 'PASS: career submission and idempotent replay (%s)\n' "$id"
status_url="$gateway/api/v1/public/applications/$id?tenantId=$OPENHIRE_TENANT_ID"
denied="$(curl --silent --show-error --max-time 15 --output /dev/null --write-out '%{http_code}' "$status_url" -H 'X-Application-Receipt: invalid')"
[[ "$denied" == 403 ]] || { printf 'FAILED: incorrect receipt returned HTTP %s\n' "$denied" >&2; exit 1; }
complete=false
for attempt in $(seq 1 60); do
  result="$(curl --fail --silent --show-error --max-time 15 "$status_url" -H "X-Application-Receipt: $receipt")"
  stage="$(printf '%s' "$result" | field stage)"
  if [[ "$stage" == AI_ASSESSED ]]; then complete=true; break; fi
  if [[ "$stage" == NEEDS_ATTENTION ]]; then
    printf 'NEEDS_ATTENTION: review eligibility answers or scanner/extractor health in the recruiter queue.\n' >&2
    exit 1
  fi
  sleep 3
done
[[ "$complete" == true ]] || { printf 'FAILED: processing did not finish within the polling window\n' >&2; exit 1; }
printf 'PASS: resume scan, extraction and keyword screening completed\n'
curl --fail --silent --show-error --max-time 15 -X POST \
  "$gateway/api/v1/public/applications/$id/withdraw?tenantId=$OPENHIRE_TENANT_ID" -H "X-Application-Receipt: $receipt" >/dev/null
[[ "$(curl --fail --silent --show-error --max-time 15 "$status_url" -H "X-Application-Receipt: $receipt" | field stage)" == WITHDRAWN ]]
printf 'PASS: receipt-protected withdrawal persisted. Application %s is retained as test data.\n' "$id"
printf 'Still review agency assignment, Candidate 360, merge recovery, tenant isolation and the load test separately.\n'
