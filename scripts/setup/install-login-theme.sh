#!/usr/bin/env bash
# Install assets into the existing container without recreating Keycloak or touching accounts/clients.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TASK_PROJECT="${OPENHIRE_DOCKER_PROJECT:-openhire}"
TASK_CONTAINER="$(docker compose --project-name "$TASK_PROJECT" -f "$TASK_ROOT/docker-compose.yml" ps -q keycloak)"
if [[ -z "$TASK_CONTAINER" ]]; then
  echo 'Keycloak must be running. No containers were changed.' >&2
  exit 1
fi
docker cp "$TASK_ROOT/infra/keycloak/themes/openhire" "$TASK_CONTAINER:/opt/keycloak/themes/"
echo 'OpenHire theme installed. No accounts, realm settings or clients were modified.'
echo 'Operator step: openhire-staff > Clients > openhire-platform and openhire-tenant > Settings > Login settings > Login theme: openhire > Save.'
echo 'Also enable Realm settings > Login > Forgot password if recovery is required.'
