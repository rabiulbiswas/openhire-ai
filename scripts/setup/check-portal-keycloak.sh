#!/usr/bin/env bash
# Read-only gate: never silently recreate an ephemeral Keycloak or mix token issuers.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TASK_PROJECT="${OPENHIRE_DOCKER_PROJECT:-openhire}"
TASK_CONTAINER="$(docker compose --project-name "$TASK_PROJECT" -f "$TASK_ROOT/docker-compose.yml" ps --all --quiet keycloak)"
[[ -n "$TASK_CONTAINER" ]] || exit 0
TASK_HOST="$(docker inspect --format '{{range .Config.Env}}{{if eq (index (split . "=") 0) "KC_HOSTNAME"}}{{println .}}{{end}}{{end}}' "$TASK_CONTAINER")"
TASK_MODE="$(docker inspect --format '{{range .Config.Env}}{{$key := index (split . "=") 0}}{{if or (eq $key "KC_HOSTNAME_STRICT") (eq $key "KC_HTTP_RELATIVE_PATH")}}{{println .}}{{end}}{{end}}' "$TASK_CONTAINER")"
TASK_STORAGE="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/opt/keycloak/data"}}{{println .Name}}{{end}}{{end}}' "$TASK_CONTAINER")"
if [[ ( -n "$TASK_HOST" && "$TASK_HOST" != 'KC_HOSTNAME=' ) || "$TASK_MODE" != *'KC_HOSTNAME_STRICT=false'* \
      || "$TASK_MODE" != *'KC_HTTP_RELATIVE_PATH=/auth'* || "$TASK_STORAGE" != "${TASK_PROJECT}_keycloak-h2-data" ]]; then
  printf '%s\n' 'Portal authentication cutover is required before deploying.' \
    'Do not recreate an ephemeral Keycloak without preserving its data.' \
    'Follow docs/SAME_HOST_AUTH.md: prepare-portal-keycloak.sh --apply, then recreate only Keycloak using the portal override.' \
    'No accounts, client settings, containers or volumes were changed by this check.' >&2
  exit 1
fi
