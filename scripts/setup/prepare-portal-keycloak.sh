#!/usr/bin/env bash
# Operator-run offline H2 snapshot and copy; never invoked automatically by deployment.
set -euo pipefail
[[ "${1:-}" == --apply && $# == 1 ]] || {
  printf '%s\n' 'Usage: bash scripts/setup/prepare-portal-keycloak.sh --apply' \
    'Briefly stops an existing local H2 Keycloak, preserves a restricted backup, copies H2 into the portal volume,' \
    'and leaves it STOPPED on success for the cutover. Restarts the original on failure if it was running.' \
    'Does not recreate it, change accounts, or configure clients. Local dev-file H2 only.'
  exit 2
}
TASK_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TASK_PROJECT="${OPENHIRE_DOCKER_PROJECT:-openhire}"
TASK_VOLUME="${TASK_PROJECT}_keycloak-h2-data"
TASK_CONTAINER="$(docker compose --project-name "$TASK_PROJECT" -f "$TASK_ROOT/docker-compose.yml" ps --all --quiet keycloak)"
if [[ -z "$TASK_CONTAINER" ]]; then
  docker volume create "$TASK_VOLUME" >/dev/null
  printf 'No existing Keycloak found. Portal storage is ready; import/provision realms normally.\n'
  exit 0
fi
TASK_EXISTING="$(docker inspect --format '{{range .Mounts}}{{if eq .Destination "/opt/keycloak/data"}}{{println .Name}}{{end}}{{end}}' "$TASK_CONTAINER")"
if [[ "$TASK_EXISTING" == "$TASK_VOLUME" ]]; then
  printf 'Keycloak already uses the portal H2 volume; no data copied or containers stopped.\n'
  exit 0
fi
TASK_IMAGE="$(docker inspect --format '{{.Image}}' "$TASK_CONTAINER")"
TASK_DB="$(docker inspect --format '{{range .Config.Env}}{{if eq (index (split . "=") 0) "KC_DB"}}{{println .}}{{end}}{{end}}' "$TASK_CONTAINER")"
if [[ -n "$TASK_DB" && "$TASK_DB" != 'KC_DB=dev-file' ]]; then
  echo 'This helper supports local dev-file H2 only; use the external database backup procedure.' >&2
  exit 1
fi
TASK_RUNNING="$(docker inspect --format '{{.State.Running}}' "$TASK_CONTAINER")"
umask 077
mkdir -p "$TASK_ROOT/backups"
TASK_BACKUP="$(mktemp -d "$TASK_ROOT/backups/keycloak-h2-XXXXXXXX")"
TASK_RESTART=0
restore_original() {
  if [[ "$TASK_RESTART" == 1 ]]; then docker start "$TASK_CONTAINER" >/dev/null || true; fi
}
trap restore_original EXIT
docker volume create "$TASK_VOLUME" >/dev/null
# Never overwrite a pre-existing target. The source and backup are retained on every failure.
docker run --rm --user 0 --entrypoint /bin/bash \
  --mount "type=volume,src=$TASK_VOLUME,dst=/snapshot" "$TASK_IMAGE" \
  -ec 'test -z "$(ls -A /snapshot)" || { echo "Target volume is not empty; refusing to overwrite it." >&2; exit 1; }'
if [[ "$TASK_RUNNING" == true ]]; then
  TASK_RESTART=1
  docker stop --time 60 "$TASK_CONTAINER" >/dev/null
fi
docker cp "$TASK_CONTAINER:/opt/keycloak/data/h2" "$TASK_BACKUP/h2"
[[ -n "$(ls -A "$TASK_BACKUP/h2")" ]] || { echo 'No H2 files found; inspect the configured database before proceeding.' >&2; exit 1; }
docker run --rm --user 0 --entrypoint /bin/bash \
  --mount "type=volume,src=$TASK_VOLUME,dst=/snapshot" \
  --mount "type=bind,src=$TASK_BACKUP/h2,dst=/source,readonly" "$TASK_IMAGE" \
  -ec 'test -z "$(ls -A /snapshot)"; mkdir /snapshot/h2; cp -a /source/. /snapshot/h2/; chown -R 1000:0 /snapshot'
TASK_RESTART=0
printf 'Offline H2 snapshot preserved at %s (contains sensitive realm/account data; do not commit/share).\n' "$TASK_BACKUP"
printf '%s\n' 'Portal volume prepared. The original container is preserved and STOPPED to prevent post-snapshot writes.' \
  'Next: follow docs/TENANT_PORTALS.md to recreate Keycloak and apply the portal issuer to ALL services.'
