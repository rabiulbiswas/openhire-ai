#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
PROJECT_NAME="${OPENHIRE_DOCKER_PROJECT:-openhire}"
WAIT_SECONDS="${OPENHIRE_STARTUP_TIMEOUT:-240}"

compose() {
  docker compose --project-name "${PROJECT_NAME}" --file "${COMPOSE_FILE}" "$@"
}

usage() {
  printf '%s\n' \
    "Usage: ./run-backend.sh [command]" \
    "" \
    "Commands:" \
    "  deploy   Build/recreate all or named backend applications and follow live logs (deploy --help for options)" \
    "  up       Build and start infrastructure plus Phase 1 and Phase 2 services (uses host MongoDB) (default)" \
    "  phase3   Build and start Phase 3 services, resume scanning/extraction and existing services" \
    "  phase4   Build and start Phase 4 interview, communication and delivery services (includes Phase 3)" \
    "  phase5   Build and start Phase 5 offer and employee services (includes Phases 3 and 4)" \
    "  phase6   Build and start Phase 6 AI gateway, AI workers and talent services (includes Phases 3 to 5; needs Ollama on the host)" \
    "  phase7   Build and start Phase 7 compliance and analytics services (includes Phases 3 to 6)" \
    "  monitoring Start only optional Prometheus (192 MB cap; existing analytics-service required for metrics)" \
    "  phase8   Build/start only integration-hub; existing Mongo, Kafka, Keycloak and gateway required" \
    "  stop     Stop containers without deleting them" \
    "  restart  Restart all containers and wait for backend health" \
    "  down     Stop and remove containers; named data volumes are preserved" \
    "  status   Show container and health status" \
    "  logs     Follow logs; optionally add service names, for example: logs api-gateway" \
    "  help     Show this help"
}

require_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    printf 'Docker is not installed or is not available on PATH.\n' >&2
    exit 1
  fi
  if ! docker compose version >/dev/null 2>&1; then
    printf 'Docker Compose v2 is required.\n' >&2
    exit 1
  fi
  if ! docker info >/dev/null 2>&1; then
    printf 'Docker is installed, but the Docker daemon is not running.\n' >&2
    exit 1
  fi
}

container_health() {
  local service="$1"
  local container_id
  container_id="$(compose ps --quiet "${service}")"
  if [[ -z "${container_id}" ]]; then
    printf 'missing'
    return
  fi
  docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}" 2>/dev/null || printf 'unknown'
}

wait_for_service() {
  local service="$1"
  local elapsed=0
  local health
  printf 'Waiting for %s' "${service}"
  while (( elapsed < WAIT_SECONDS )); do
    health="$(container_health "${service}")"
    if [[ "${health}" == "healthy" || "${health}" == "running" ]]; then
      printf ' ready\n'
      return 0
    fi
    if [[ "${health}" == "unhealthy" || "${health}" == "exited" || "${health}" == "dead" ]]; then
      printf ' failed (%s)\n' "${health}" >&2
      compose logs --tail 100 "${service}" >&2
      return 1
    fi
    printf '.'
    sleep 2
    elapsed=$((elapsed + 2))
  done
  printf ' timed out after %ss\n' "${WAIT_SECONDS}" >&2
  compose logs --tail 100 "${service}" >&2
  return 1
}

start_stack() {
  printf 'Building and starting the OpenHire backend stack...\n'
  if ! compose up --detach --build --remove-orphans; then
    compose logs --tail 100 >&2 || true
    exit 1
  fi
  wait_for_service keycloak
  wait_for_service redis
  wait_for_service kafka
  wait_for_service identity-service
  wait_for_service job-service
  wait_for_service approval-service
  wait_for_service api-gateway
  printf '\nOpenHire backend is ready.\n'
  printf 'Gateway:          http://localhost:%s\n' "${GATEWAY_PORT:-8080}"
  printf 'Keycloak:         http://localhost:%s\n' "${KEYCLOAK_PORT:-8180}"
  printf 'Gateway health:   http://localhost:%s/actuator/health\n' "${GATEWAY_PORT:-8080}"
  printf 'Identity health:  http://localhost:%s/actuator/health\n' "${IDENTITY_PORT:-8081}"
  printf 'Job health:       http://localhost:%s/actuator/health\n' "${JOB_SERVICE_PORT:-8082}"
  printf 'Approval health:  http://localhost:%s/actuator/health\n' "${APPROVAL_SERVICE_PORT:-8083}"
  printf 'Follow logs with: %s/run-backend.sh logs\n' "${SCRIPT_DIR}"
}

command_name="${1:-up}"
if (( $# > 0 )); then shift; fi

if [[ "${command_name}" == "help" || "${command_name}" == "-h" || "${command_name}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ "${command_name}" == "deploy" ]]; then
  exec bash "${SCRIPT_DIR}/scripts/deployment/deploy-local.sh" "$@"
fi

require_docker

case "${command_name}" in
  up)
    start_stack
    ;;
  phase3)
    export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}phase3"
    start_stack
    wait_for_service application-service
    wait_for_service candidate-service
    wait_for_service resume-processor
    ;;
  phase4)
    export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}phase3,phase4"
    start_stack
    wait_for_service application-service
    wait_for_service candidate-service
    wait_for_service resume-processor
    wait_for_service mailpit
    wait_for_service interview-service
    wait_for_service communication-service
    wait_for_service notification-dispatcher
    printf 'Mailpit inbox:    http://localhost:%s\n' "${MAILPIT_UI_PORT:-8025}"
    ;;
  phase5)
    export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}phase3,phase4,phase5"
    start_stack
    wait_for_service application-service
    wait_for_service candidate-service
    wait_for_service resume-processor
    wait_for_service mailpit
    wait_for_service interview-service
    wait_for_service communication-service
    wait_for_service notification-dispatcher
    wait_for_service offer-service
    wait_for_service employee-service
    printf 'Mailpit inbox:    http://localhost:%s\n' "${MAILPIT_UI_PORT:-8025}"
    ;;
  phase6)
    export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}phase3,phase4,phase5,phase6"
    start_stack
    wait_for_service application-service
    wait_for_service candidate-service
    wait_for_service resume-processor
    wait_for_service mailpit
    wait_for_service interview-service
    wait_for_service communication-service
    wait_for_service notification-dispatcher
    wait_for_service offer-service
    wait_for_service employee-service
    wait_for_service ai-gateway
    wait_for_service ai-workers
    wait_for_service talent-service
    printf 'Mailpit inbox:    http://localhost:%s\n' "${MAILPIT_UI_PORT:-8025}"
    printf 'Local model:      %s (start it first: ollama serve)\n' "${OLLAMA_BASE_URL:-http://localhost:11434}"
    ;;
  monitoring)
    compose --profile monitoring up -d --no-deps prometheus
    printf 'Prometheus (192 MB cap): http://localhost:%s\n' "${PROMETHEUS_PORT:-9090}"
    ;;
  phase8)
    compose --profile phase8 up -d --no-deps --build integration-hub
    wait_for_service integration-hub
    printf 'Integration hub: http://localhost:%s\n' "${INTEGRATION_HUB_HOST_PORT:-8107}"
    ;;
  phase7)
    export COMPOSE_PROFILES="${COMPOSE_PROFILES:+${COMPOSE_PROFILES},}phase3,phase4,phase5,phase6,phase7"
    start_stack
    for service in application-service candidate-service resume-processor mailpit interview-service communication-service notification-dispatcher offer-service employee-service ai-gateway ai-workers talent-service compliance-service analytics-service; do
      wait_for_service "$service"
    done
    printf 'Mailpit inbox:    http://localhost:%s\n' "${MAILPIT_UI_PORT:-8025}"
    ;;
  stop)
    compose stop
    ;;
  restart)
    compose restart
    wait_for_service keycloak
    wait_for_service redis
    wait_for_service kafka
    wait_for_service identity-service
    wait_for_service job-service
    wait_for_service approval-service
    wait_for_service api-gateway
    ;;
  down)
    compose down --remove-orphans
    ;;
  status)
    compose ps
    ;;
  logs)
    compose logs --follow --tail 200 "$@"
    ;;
  *)
    printf 'Unknown command: %s\n\n' "${command_name}" >&2
    usage >&2
    exit 2
    ;;
esac
