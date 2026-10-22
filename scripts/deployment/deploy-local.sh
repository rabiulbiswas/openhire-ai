#!/usr/bin/env bash
# Build locally in Docker, recreate selected application containers, then stream logs.
# No host Java/Gradle installation is needed. Infrastructure and volumes are retained.
set -Eeuo pipefail

BACKEND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_NAME="${OPENHIRE_DOCKER_PROJECT:-openhire}"
WAIT_SECONDS="${OPENHIRE_STARTUP_TIMEOUT:-300}"
LOG_TAIL="${OPENHIRE_LOG_TAIL:-200}"
FOLLOW_LOGS="${OPENHIRE_FOLLOW_LOGS:-1}"
BUILD_IMAGES=1
LOGS_ONLY=0
NO_SCAN=0
TENANT_PORTALS=0
ALL_SERVICES=(identity-service job-service approval-service application-service candidate-service
  resume-processor interview-service communication-service notification-dispatcher offer-service
  employee-service ai-gateway ai-workers talent-service compliance-service analytics-service
  integration-hub api-gateway)
SERVICES=()

usage() {
  cat <<'USAGE'
Usage: ./scripts/deployment/deploy-local.sh [--no-build] [--no-follow] [service ...]
       ./run-backend.sh deploy [options] [service ...]

No service names: deploy all 18 backend applications (not the frontend or Prometheus).
Named services: recreate only those applications; other business dependencies must already run.
Required shared infrastructure is started if needed, never forcibly recreated or removed.

Options:
  --no-build    Use existing images; fail if a required application image is missing
  --no-scan     Local trusted-fixture mode: disable resume scanning via Compose override
  --tenant-portals Enable the tenant proxy after operator-managed Keycloak setup
  --follow      Force continuous combined logs (overrides OPENHIRE_FOLLOW_LOGS=0)
  --logs-only   Watch existing services without building or restarting anything
  --no-follow   Return after startup checks instead of following logs
  -h, --help   Show this help without contacting Docker

Environment:
  OPENHIRE_DOCKER_PROJECT   Compose project (default: openhire)
  OPENHIRE_STARTUP_TIMEOUT Startup wait timeout in seconds (default: 300)
  OPENHIRE_LOG_TAIL        Initial log lines per service (default: 200)
  OPENHIRE_FOLLOW_LOGS     1 to follow, 0 to return after startup (default: 1)

Examples:
  ./scripts/deployment/deploy-local.sh
  ./scripts/deployment/deploy-local.sh identity-service api-gateway
  ./scripts/deployment/deploy-local.sh --no-build --no-follow job-service

MongoDB must already run on the host or at your configured URI. No LLM is started.
Ctrl+C while following logs leaves services running. Raw timestamped Compose logs
preserve stack traces; no separate formatter or Python dependency is required.
USAGE
}

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
contains() {
  local wanted="$1" item
  shift
  for item in "$@"; do [[ "$item" != "$wanted" ]] || return 0; done
  return 1
}

for argument in "$@"; do
  case "$argument" in
    --help|-h) usage; exit 0 ;;
    --no-build) BUILD_IMAGES=0 ;;
    --no-scan) NO_SCAN=1 ;;
    --tenant-portals) TENANT_PORTALS=1 ;;
    --no-follow) FOLLOW_LOGS=0 ;;
    --follow|--combined-logs) FOLLOW_LOGS=1 ;;
    --logs-only) LOGS_ONLY=1; FOLLOW_LOGS=1 ;;
    -*) fail "Unknown option: $argument" ;;
    *)
      contains "$argument" "${ALL_SERVICES[@]}" || fail "Unknown backend service: $argument"
      if ! contains "$argument" "${SERVICES[@]+${SERVICES[@]}}"; then SERVICES+=("$argument"); fi
      ;;
  esac
done
if (( ${#SERVICES[@]} == 0 )); then SERVICES=("${ALL_SERVICES[@]}"); fi
[[ "$WAIT_SECONDS" =~ ^[1-9][0-9]*$ ]] || fail 'OPENHIRE_STARTUP_TIMEOUT must be a positive integer'
[[ "$LOG_TAIL" =~ ^[0-9]+$ ]] || fail 'OPENHIRE_LOG_TAIL must be a nonnegative integer'
[[ "$FOLLOW_LOGS" == 0 || "$FOLLOW_LOGS" == 1 ]] || fail 'OPENHIRE_FOLLOW_LOGS must be 0 or 1'

# Resolve .env and relative Compose paths from backend regardless of the caller's cwd.
cd "$BACKEND_DIR"
COMPOSE_FILES=(--file "$BACKEND_DIR/docker-compose.yml")
if (( NO_SCAN )); then
  COMPOSE_FILES+=(--file "$BACKEND_DIR/docker-compose.local-no-scan.yml")
fi
if (( TENANT_PORTALS )); then
  COMPOSE_FILES+=(--file "$BACKEND_DIR/docker-compose.tenant-portals.yml")
fi
compose() {
  docker compose --project-name "$PROJECT_NAME" "${COMPOSE_FILES[@]}" \
    --parallel 1 --profile phase3 --profile phase4 --profile phase5 --profile phase6 \
    --profile phase7 --profile phase8 "$@"
}

command -v docker >/dev/null 2>&1 || fail 'Docker is not available on PATH'
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'
docker info >/dev/null 2>&1 || fail 'Docker daemon is not running'
compose config --quiet

follow_logs() {
  printf '\nFollowing combined backend logs (last %s lines per service).\n' "$LOG_TAIL"
  printf 'Press Ctrl+C to stop watching; containers keep running.\n'
  trap 'printf "\nStopped watching logs; containers remain running.\n"; exit 0' INT TERM
  while :; do
    status=0
    compose logs --follow --timestamps --tail "$LOG_TAIL" "${SERVICES[@]}" || status=$?
    printf '\nLog stream ended (status %s); reconnecting in 3 seconds. Ctrl+C to stop.\n' "$status" >&2
    sleep 3
  done
}

if (( LOGS_ONLY )); then
  [[ "$FOLLOW_LOGS" == 1 ]] || fail '--logs-only cannot be combined with --no-follow'
  follow_logs
  exit 0
fi

printf 'OpenHire local deployment\nProject: %s\nApplications: %s\n' "$PROJECT_NAME" "${SERVICES[*]}"
printf 'Host MongoDB is required. Existing infrastructure, images and data volumes are retained.\n'
printf 'Building/running all services still requires sufficient memory; this script does not change Docker limits.\n'
if (( NO_SCAN )); then
  printf 'WARNING: local no-scan mode selected. Use trusted synthetic resumes only.\n'
  if ! contains resume-processor "${SERVICES[@]}"; then
    printf 'Resume processor is not selected: its running configuration and ClamAV will not be changed.\n'
  fi
fi

if (( BUILD_IMAGES )); then
  printf '\nBuilding application images (one build at a time)...\n'
  compose build "${SERVICES[@]}"
fi

INFRA=(keycloak redis kafka)
if (( TENANT_PORTALS )) && ! contains notification-dispatcher "${SERVICES[@]}"; then INFRA+=(mailpit); fi
if contains resume-processor "${SERVICES[@]}"; then
  INFRA+=(tika)
  if (( ! NO_SCAN )); then INFRA+=(clamav); fi
fi
if contains notification-dispatcher "${SERVICES[@]}"; then INFRA+=(mailpit); fi

diagnostics() {
  compose ps --all >&2 || true
  compose logs --no-color --timestamps --tail "$LOG_TAIL" "${INFRA[@]}" "${SERVICES[@]}" >&2 || true
}

printf '\nStarting required infrastructure without recreating existing containers...\n'
if ! compose up --detach --no-recreate --no-build --wait --wait-timeout "$WAIT_SECONDS" "${INFRA[@]}"; then
  printf 'Infrastructure startup failed. Application deployment was not started.\n' >&2
  diagnostics
  exit 1
fi

if (( TENANT_PORTALS )); then
  OPENHIRE_DOCKER_PROJECT="$PROJECT_NAME" bash "$BACKEND_DIR/scripts/setup/install-login-theme.sh"
fi

printf '\nRecreating selected application containers and checking startup...\n'
if ! compose up --detach --no-deps --no-build --pull never --force-recreate \
  --wait --wait-timeout "$WAIT_SECONDS" "${SERVICES[@]}"; then
  printf 'Application startup failed. Containers are retained for inspection; no success is claimed.\n' >&2
  diagnostics
  exit 1
fi

printf '\nSelected applications passed Compose startup checks (not end-to-end acceptance).\n'
if (( NO_SCAN )) && contains resume-processor "${SERVICES[@]}"; then
  printf 'Stopping ClamAV after successful no-scan deployment; signature volume is preserved.\n'
  compose stop clamav
fi
compose ps "${SERVICES[@]}"
if (( TENANT_PORTALS )); then
  printf '\nStarting tenant proxy; Keycloak clients and exact URLs must be configured by an operator.\n'
  compose up --detach --no-deps tenant-proxy
fi
if [[ "$FOLLOW_LOGS" == 1 ]]; then
  follow_logs
else
  printf '\nLive logs disabled by --no-follow or OPENHIRE_FOLLOW_LOGS=0.\n'
  printf 'Watch without redeploying: ./scripts/deployment/deploy-local.sh --logs-only\n'
fi
