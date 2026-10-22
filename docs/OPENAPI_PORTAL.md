# OpenAPI service browser

The gateway hosts one authenticated Swagger UI at **http://localhost:8080/swagger-ui/index.html**.
Its service dropdown includes all 18 runnable modules; shared libraries are not separate APIs. Each selected
document is generated from that service's current running code, not from the historical contract files.

## Enable locally

Documentation is disabled by default, including in Compose. Enable it only in a trusted development/testing
environment. Keep `SECURITY_MODE=oidc` and the gateway OIDC profile enabled; local bypass mode intentionally returns
404 for documentation instead of exposing a portal without authentication.

From the backend repository root, after reviewing your existing OIDC setup:

```bash
# Rebuild/recreate with documentation enabled; you run this manually
OPENHIRE_API_DOCS_ENABLED=true ./run-backend.sh deploy
```

Alternatively, add `OPENHIRE_API_DOCS_ENABLED=true` to your existing private root `.env`, preserving other settings,
then rebuild/recreate. Merely restarting old images does not install the new code or change container environment.
You can deploy selected services plus `api-gateway` if resources are limited; unstarted services remain in the
dropdown and cannot serve their documents. This command does not change Docker memory or start the frontend/LLM.

Open the portal and sign in through **staff Keycloak**. The gateway remembers that this login started from Swagger
and redirects back to the portal instead of the application home page. Existing normal application login routing
is preserved. Candidate sessions and API keys do not grant portal access. If currently signed in as a candidate,
use a separate browser session or sign out before staff login. Avoid bypassing authentication to fix a redirect.

## Routes and behavior

| Gateway URL | Purpose |
| --- | --- |
| `/swagger-ui/index.html` | Service dropdown and endpoint/schema browser |
| `/openapi/config` | Fixed service catalogue and browse-only UI settings |
| `/v3/api-docs` | Gateway-owned BFF endpoint specification, not all proxied business APIs |
| `/openapi/services/<service-name>` | GET-only proxy to the selected service's OpenAPI JSON, relaying the staff token |

Service destinations use the existing `*_SERVICE_URL` environment variables. Compose already supplies internal
Docker addresses. Direct JVM runs must use reachable service ports; later-phase Compose host ports differ from
their container/default JVM ports. The job service retains `/api-docs`; other services expose `/v3/api-docs` when
enabled. No browser-controlled upstream URL or generic open proxy is provided.

The UI is **browse-only**: supported execution methods are empty, Try it out is disabled, query-parameter config
overrides are disabled, authorization is not persisted in browser storage, and no external Swagger validator is
contacted. This is a UI behavior, not a replacement for API authorization: normal application clients can still call
authorized business endpoints. Existing service authorization and CSRF behavior remain in force.

Internal service endpoint patterns and Actuator/error paths are excluded. Generated schemas reflect annotations
and Java types; map-based/dynamic payloads may need further schema annotations. The portal does not certify that
the older files under `contracts/` match implementation. A service must be running and reachable to load its spec.
Service specs may advertise their own server/path context; API execution through the portal is deliberately not enabled.

## Troubleshooting

- **Keycloak login:** expected before staff authentication. A successful portal-started staff login returns to Swagger.
- **404:** check `OPENHIRE_API_DOCS_ENABLED` on the gateway and selected service, OIDC mode and rebuilt images.
- **401 fetching JSON:** staff session/token may have expired; reopen the portal and sign in again. JSON routes do not
  redirect unauthenticated fetches through Keycloak.
- **403:** candidate/non-staff session, or insufficient downstream authentication. Do not make the docs routes public.
- **Failed to load selected definition / 5xx:** check the selected service's health, URL, documentation flag and logs.
- **Missing endpoints:** internal endpoints are intentionally hidden; gateway selection lists BFF handlers only.
- **No Try it out button:** intentional for this initial release.

## Verification and deployment boundaries

```bash
# Lightweight configuration/catalogue regression tests
node --test .github/scripts/openapi.test.mjs

# Focused Java tests for the gateway (compiles test dependencies; no full stack required)
bash gradlew :api-gateway:test \
  --tests '*OpenApiPortalConfigurationTest' \
  --tests '*DocumentationAccessTest' --no-daemon
```

Dependency lockfiles are updated with Springdoc 3.1.1 for the Spring Boot 4 stack. No new infrastructure service or
frontend change is required. Do not enable documentation in production by default; restrict ingress and identities
if an approved production use case requires it. Kubernetes deployments must explicitly supply the flag to each
enabled workload; Helm defaults do not enable it. CI image publishing and deployment remain disabled.

Validation must include a real staff login and loading each live service document after you rebuild. Static checks
and source-level tests alone do not prove that your running containers, Keycloak client secrets and sessions work.
