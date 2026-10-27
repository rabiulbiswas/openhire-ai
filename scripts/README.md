# Backend scripts

Run the commands below from the backend repository root. These tools are not Java service runtime dependencies.
The scripts were reorganized into four folders; use these paths instead of the old flat `scripts/<file>` paths.

| Folder | Contents | When to use |
| --- | --- | --- |
| `deployment/` | `deploy-local.sh` | Build/recreate local Docker services and follow logs |
| `testing/` | `verify-phase*`, load tests, synthetic fixtures, token helper and script unit tests | Developer acceptance, regression and performance checks |
| `setup/` | Keycloak profile and enterprise identity configuration | Explicit operator-run identity setup/migration |
| `maintenance/` | Mongo recovery, service-reference checks and Phase 7 preflight | Backup/recovery, documentation upkeep and operational checks |

## Deployment

For `--tenant-portals`, authentication stays on each portal hostname under `/auth/...`; validators use
fixed internal JWKS with constrained local per-host issuers. `setup/check-portal-keycloak.sh` is a read-only
deployment gate. Existing local H2 installations
must explicitly run `bash scripts/setup/prepare-portal-keycloak.sh --apply` and follow the coordinated
[cutover procedure](../docs/SAME_HOST_AUTH.md) before deployment. That helper temporarily stops Keycloak,
saves a restricted offline backup, prepares the persistent volume, and leaves the source stopped on success.
It is never run automatically. No user/client setup is performed. Do not use `down -v`.

For local testing without ClamAV, use the explicit `--no-scan` option:

```bash
# Build/recreate resume processor, stop ClamAV after startup succeeds, follow logs
./scripts/deployment/deploy-local.sh --no-scan --follow resume-processor
# Deploy all backend applications in that mode and follow combined logs
./scripts/deployment/deploy-local.sh --no-scan --follow
```

This loads `docker-compose.local-no-scan.yml`, skips ClamAV startup, and sets
`RESUME_SCAN_ENABLED=false` with the local-only profile. Requires Compose 2.24.4+.
Use trusted synthetic files only. ClamAV is stopped only when resume-processor
was selected and its deployment passed startup checks. `--logs-only` never
changes services. Use `--no-scan` on each deployment that should retain the bypass;
omitting it restores normal scanning when resume-processor is deployed.


After all selected applications pass startup checks, deployment follows their
combined live logs continuously in the same console. No tmux or extra windows
are needed. If the log stream ends, the watcher retries after three seconds.

```bash
# Watch already-deployed applications, without a build or restart
./scripts/deployment/deploy-local.sh --logs-only
# Deploy and explicitly enable continuous combined logs
./scripts/deployment/deploy-local.sh --follow
# Watch only selected services
./scripts/deployment/deploy-local.sh --logs-only job-service api-gateway
```

Ctrl+C stops watching without stopping containers. Reconnection can repeat the
configured last 200 lines. A quiet console means no new application messages;
the watcher does not invent heartbeat messages. `--no-follow` or
`OPENHIRE_FOLLOW_LOGS=0` disables following; `--follow` overrides that environment
setting. Deployment failures still print diagnostics and exit nonzero instead
of claiming success. Use `--logs-only` to investigate existing containers then.
The combined stream covers selected application services, not infrastructure.

For local role-based UI logins, see [demo user setup](../docs/LOCAL_DEMO_USERS.md).
`node scripts/setup/seed-ui-users.mjs` prints the account inventory; `--apply` explicitly provisions them in local Keycloak.

```bash
# Existing entry point is unchanged
./run-backend.sh deploy --help
./run-backend.sh deploy job-service
# Equivalent direct tool
bash scripts/deployment/deploy-local.sh --help
```

Deploying builds images and changes containers. Mongo must already be reachable; this does not start a frontend or LLM.
See the [root README](../README.md) for prerequisites and stop/log commands.

## Testing

```bash
# Isolated helper tests; no live deployment or database restoration
node --test .github/scripts/*.test.mjs scripts/testing/*.test.mjs
# Syntax checks only
bash -n scripts/deployment/deploy-local.sh
```

`verify-phase*` scripts are optional **live acceptance** tools, not the isolated unit tests above. Several create
users, applications, offers or messages; some support deliberate dependency outages. Read their source header and
phase guide before running them, use a dedicated development tenant, and supply the required environment variables.
`phase3-load.js` uses k6. Resume/OCR and AI acceptance require their respective scanner/parser/model dependencies;
the optional image-only PDF generator also needs the Sharp dependency used by its fixture code.

Keep fixture files and `phase3-staff-token.mjs` with the testing tools: other scripts import them.
The `*.test.mjs` files test deployment/maintenance tooling, tenant configuration and package contracts, and are
invoked by CI. Run `node scripts/maintenance/package-layout.mjs` for package alignment, HTTP mapping and
persisted class-name compatibility checks; see [backend package architecture](../docs/BACKEND_PACKAGE_ARCHITECTURE.md).

## Setup

`setup/configure-keycloak-profile.mjs` and `setup/configure-enterprise-identity.mjs` are configuration tools,
not normal startup steps. They may modify identity-provider settings. Review their inputs and documented apply
behavior before use; do not run them against production just to check that the files work.

## Maintenance

```bash
# Read-only check of generated endpoint documentation
node scripts/maintenance/service-reference.mjs --check
```

`maintenance/mongo-recovery.mjs` supplies encrypted backup and isolated restore rehearsal tooling. Follow
[enterprise recovery instructions](../docs/PHASE_8_ENTERPRISE_RECOVERY.md); review database targets and credentials.
`maintenance/phase7-preflight.mjs` exports the read-only dependency check used by Phase 7 acceptance; it is not
a standalone deployment command.

No scripts were removed from the toolset. `.github/scripts/` remains separate because it contains pipeline-specific
change detection, release and deployment helpers rather than the operator tools in this directory.

## Branded login assets

`bash scripts/setup/install-login-theme.sh` copies the OpenHire login theme into the running Keycloak
container without restarting it. It does not modify clients, users, callback allowlists or credentials.
An operator selects `openhire` under each tenant/platform client's Login theme setting afterward.
`deploy-local.sh --tenant-portals` includes this asset installation. See `docs/TENANT_PORTALS.md` for setup.
