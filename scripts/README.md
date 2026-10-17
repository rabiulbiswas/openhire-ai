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
The two `*.test.mjs` files test tools under `deployment/` and `maintenance/`, and are invoked by CI.

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
