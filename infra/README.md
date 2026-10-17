# Infrastructure guide

`infra/` contains configuration for systems that support the backend. It is not business-service Java code and
does not run by itself. Docker Compose loads some files for local development; Helm uses others for Kubernetes.
Operational commands live in [`scripts/`](../scripts/README.md), not here.

## Directory map

```text
infra/
├── README.md
├── keycloak/
│   ├── README.md
│   ├── openhire-staff-realm.json
│   └── openhire-candidate-realm.json
├── helm/openhire/
│   ├── Chart.yaml
│   ├── README.md
│   ├── values.yaml
│   └── templates/
│       ├── workloads.yaml
│       └── network.yaml
└── prometheus/
    ├── prometheus.yml
    ├── alerts.yml
    ├── alerts.test.yml
    └── internal-secret.local
```

## Which parts do I need?

| Component | Purpose | When used |
| --- | --- | --- |
| Keycloak | Authenticates staff and candidates and issues OIDC tokens | Normal local OIDC sign-in and future deployed environments |
| Helm chart | Describes how backend application containers run in Kubernetes | Offline CI validation now; a reviewed Kubernetes/EKS deployment later |
| Prometheus | Collects operational metrics and evaluates alert rules | Optional local monitoring; not needed for a normal Java build |

The current GitHub pipeline builds/tests and validates Helm, but image publication and deployment remain disabled.
These files do not create AWS resources. See the [pipeline guide](../.github/README.md).

## 1. Keycloak — login and identity

A **realm** is a separate authentication configuration and user directory. Staff and candidates use different realms.
Keycloak handles authentication; the gateway maintains the browser session, and backend services still enforce
tenant/business permissions. Keycloak is not a replacement for service authorization or application data in MongoDB.

| File | Contents and purpose |
| --- | --- |
| [keycloak/README.md](keycloak/README.md) | Client-secret, browser-flow and production setup notes |
| [openhire-staff-realm.json](keycloak/openhire-staff-realm.json) | `openhire-staff` realm, `openhire-web` and `openhire-service` clients, staff roles and development fixture users |
| [openhire-candidate-realm.json](keycloak/openhire-candidate-realm.json) | `openhire-candidate` realm, `openhire-candidate-web` client, candidate role and development fixture user |

Local Compose mounts the JSON files read-only into Keycloak's import directory and starts Keycloak with
`start-dev --import-realm`. Its default public address is `http://localhost:8180`; containers reach it at
`http://keycloak:8080`. Browser issuer/redirect URLs must remain reachable from the browser, not just Docker.

The gateway login entry points are `/oauth2/authorization/openhire-staff` and
`/oauth2/authorization/openhire-candidate`. Browser flows use authorization code with PKCE; direct password grants
are disabled. Match client credentials with `STAFF_OIDC_CLIENT_SECRET`, `CANDIDATE_OIDC_CLIENT_SECRET` and
`KEYCLOAK_ADMIN_CLIENT_SECRET` where required by the service configuration. Never place real production secrets
in these tracked JSON files.

**Import is not a migration:** editing a JSON file does not update an already-existing realm on startup.
Use reviewed administrator changes or the tools in [`scripts/setup/`](../scripts/setup/) when needed; do not delete
a populated realm to make an import run again. The current local Compose Keycloak service has no named data volume
or external database configured, so container recreation can lose realm changes. Plan persistence and backups
before using it for durable/shared identities.

Development users, localhost redirects, placeholder secrets and `start-dev` are not production settings. Before
deployment, configure HTTPS, persistent identity storage, real redirect origins, SMTP, required email/TOTP policies,
secret rotation and access controls. Do not expose the local administrative defaults publicly.

Read-only check after the local stack starts:

```bash
curl --fail http://localhost:8180/realms/openhire-staff/.well-known/openid-configuration
```

## 2. Helm — Kubernetes application deployment

Helm combines the chart's templates and values into Kubernetes resource definitions. This chart describes the
18 backend applications, not the frontend or their supporting infrastructure.

| File | Purpose |
| --- | --- |
| [Chart.yaml](helm/openhire/Chart.yaml) | Chart name, description and chart/application metadata versions; these do not automatically select container image tags |
| [values.yaml](helm/openhire/values.yaml) | Default image settings, enabled services, replicas, resource limits, autoscaling, ingress and network policy options |
| [templates/workloads.yaml](helm/openhire/templates/workloads.yaml) | Generates each enabled service's Deployment, internal Service, disruption budget and optional CPU autoscaler; configures probes, secrets, volumes and container security |
| [templates/network.yaml](helm/openhire/templates/network.yaml) | Generates ingress network policies and optional TLS ingress to the API gateway |
| [README.md](helm/openhire/README.md) | Chart configuration, image promotion and release-readiness instructions |

Important defaults and boundaries:

- Two replicas per enabled service; resource requests are 100m CPU/256Mi memory and limits are 1 CPU/768Mi memory.
  These are starting settings, not measured production sizing or a promise that the stack fits Docker Desktop.
- All chart services listen on **8080** through `SERVER_PORT`. Kubernetes service URLs use
  `http://<release>-<service>:8080`, not the host port numbers from local Compose.
- Each enabled service requires an existing Kubernetes Secret named `<release>-<service>` containing its environment
  configuration. The chart does not fetch AWS Secrets Manager values or install External Secrets Operator.
- `image.tag` is required. A per-service image digest can override the tag; supply real tested images, not validation tags.
- The resume processor is disabled by default. Enable it only after provisioning its existing PVC and scanner/parser
  dependencies. Review storage access modes before running multiple replicas.
- Autoscaling and ingress are disabled by default. Autoscaling needs a functioning metrics API; ingress needs the
  chosen controller, DNS and existing TLS secret.
- Network policies are enabled by default and require a network plugin that enforces them. They restrict ingress,
  not egress. Monitoring access from another namespace needs a reviewed rule.
- Containers run non-root with read-only root filesystems and writable temporary storage. Provider credential files
  can use explicitly configured read-only volumes; they are not embedded in images.

**Not provisioned:** EKS, ECR repositories, IAM permissions, MongoDB, Redis, Kafka, Keycloak, Ollama, Tika, ClamAV,
SMTP infrastructure, ingress controllers, persistent volumes, TLS certificates or runtime secrets. Arrange these
separately before deploying. A rendered chart does not prove connectivity, failover or application acceptance.

Safe local validation from the backend root (no cluster changes):

```bash
helm lint infra/helm/openhire --set image.tag=offline-test
helm template openhire infra/helm/openhire --set image.tag=offline-test > /dev/null
```

## 3. Prometheus — metrics and alerts

Prometheus periodically reads the analytics service's metrics endpoint. It stores time-series metrics and evaluates
rules; it does not collect application logs or automatically send email/Slack notifications.

| File | Purpose |
| --- | --- |
| [prometheus.yml](prometheus/prometheus.yml) | Scrapes `analytics-service:8096/api/v1/internal/platform/metrics` every 60 seconds with a 10-second timeout; supplies the internal authentication header from a mounted file and applies scrape limits |
| [alerts.yml](prometheus/alerts.yml) | Warns when the SLO error budget is exhausted for 5 minutes or the configured exporter is down for 3 minutes |
| [alerts.test.yml](prometheus/alerts.test.yml) | Synthetic rule tests for `promtool`; no live service is required |
| `internal-secret.local` | Local credential file mounted as `/etc/prometheus/internal-secret`; must match the analytics service's `INTERNAL_SERVICE_SECRET` |

Do not print or paste the secret file into tickets/logs. It is currently tracked in this checkout; `.gitignore` alone
does not untrack it or erase history. Never replace its contents with a real production credential. Use a separately
managed file through `PROMETHEUS_SECRET_FILE` for local overrides; if a real credential has been committed, rotate
it and review repository history separately.

Compose starts Prometheus only under the `monitoring` profile. It binds to `127.0.0.1:9090`, limits memory to 192 MB
and CPU to 0.5, and keeps metrics for at most 24 hours or 128 MB. Data is stored in the `prometheus-data` named volume.
Those retention settings apply to monitoring metrics—not Mongo audit records, AI logs or application retention policies.

```bash
# Starts only Prometheus; analytics-service must already be running
./run-backend.sh monitoring
curl --fail http://localhost:9090/-/ready
```

Open `http://localhost:9090` and inspect targets and alerts. Readiness alone does not mean the analytics scrape works.
Connection errors usually indicate a missing analytics service; authentication errors require checking the mounted
secret and analytics configuration. No Alertmanager/external notification routing is configured here.

Optional isolated alert test (requires Docker and may download the image; no deployment):

```bash
docker run --rm --network none \
  -v "$PWD/infra/prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro" \
  -v "$PWD/infra/prometheus/alerts.test.yml:/etc/prometheus/alerts.test.yml:ro" \
  --entrypoint promtool prom/prometheus:v3.5.0 \
  test rules /etc/prometheus/alerts.test.yml
```

The absolute `/etc/prometheus/alerts.yml` reference in the test file is why the isolated test mounts those exact paths.

## Related configuration outside infra

- [`docker-compose.yml`](../docker-compose.yml): local infrastructure images, ports, profiles, volumes and environment
  wiring, including Redis, Kafka, Tika, ClamAV and Mailpit. MongoDB and the LLM are externally supplied.
- [`run-backend.sh`](../run-backend.sh) and [`scripts/deployment/`](../scripts/deployment/): explicit local startup tools.
- [`scripts/setup/`](../scripts/setup/): reviewed identity configuration tools.
- [Phase 7 operations](../docs/PHASE_7_OPERATIONS.md) and [enterprise recovery](../docs/PHASE_8_ENTERPRISE_RECOVERY.md):
  operational procedures and acceptance requirements.

Keep infrastructure changes in review alongside application changes. Do not delete realms, named volumes or
databases as a routine fix for startup issues. Documentation and offline checks are not evidence of a live deployment.
