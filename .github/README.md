# OpenHire GitHub Actions CI/CD

## Separate repository preparation

Standalone, CI-only pipelines now live in `backend/.github/` and `frontend/.github/`. Use those directories
with their respective folder contents as each new repository's root; see their README files for setup.
The workflows in this outer directory remain the original combined-repository implementation and must not
be copied over the standalone workflows. Nested workflows do not run while still nested in this repository.
No repositories, remotes or deployments were created by this preparation.

## Current mode: build and test only

Pushes to `main` and pull requests targeting `main` run affected-service builds, tests and validation.
Manual **OpenHire CI** runs validate all services. Reports and build artifacts remain downloadable in Actions.
Image publishing, release assembly and deployment are hard-disabled in the workflow files, even if
`ENABLE_AWS_CD=true` was previously configured. The deployment workflow no longer triggers after CI;
manual deployment runs also skip all jobs. No AWS credentials or deployment settings are required now.

The deployment documentation below describes the retained **future** implementation, not active behavior.
Enabling it later requires a reviewed workflow change to restore publication/release conditions and deployment
authorization/triggers, as well as the documented AWS setup. Repository variables alone cannot enable it.

## Pipeline UI

Open **GitHub → Actions → OpenHire CI** or **OpenHire Deploy**. The native graph shows jobs, dependencies,
queued/running/failed states and per-service matrix entries. Open a job for live build/test/rollout output;
the Summary tab shows affected services and immutable image digests. Test reports are downloadable artifacts.
GitHub Environments display approval gates and deployment history. No separate dashboard server is required.

This is repository implementation, not evidence of a successful GitHub/AWS run. Nothing is deployed merely by adding
these files. Publication and deployment are currently hard-disabled in workflow code. Application startup
logs are deliberately not copied into GitHub; they can contain credentials or candidate data. Use restricted cluster
logging for application logs. Pipeline logs show Helm progress, rollout status and failure pod status.

## Change behavior

| Change | CI behavior |
| --- | --- |
| One backend service's source/build/test files | Test/build/package that service |
| `platform-starter` | All transitive dependents (currently all business services except independent API gateway) |
| `service-support` | Its transitive consumers |
| Gradle root/wrapper/catalog, Dockerfile, workflow/helpers | All backend services; conservatively verify frontend too |
| API/event contracts | All backend services |
| Helm chart only | Validate chart; no publication or deployment |
| Frontend source/config | Build/test frontend workspace; no backend rebuild |
| Backend Markdown documentation only | Pipeline validation only |
| First push or manual CI run | Full backend and frontend validation |

Deleted and renamed files are included (`git diff --no-renames`). PRs compare their base to the tested merge checkout;
pushes compare the previous pushed SHA to the current SHA. Missing history fails closed rather than guessing.
Each backend matrix job has independent reports and a tested executable JAR. Shared-library tests also run whenever
backend builds run. The complete branch-protection check is **CI gate**, which handles legitimately skipped jobs.

Mongo integration tests use disposable Mongo 8.2 on the runner. Redis is also available for context tests.
The platform smoke test uses Docker/Testcontainers for Mongo/Redis/Kafka. External providers and LLMs are mocked or
explicitly skipped. Live model, recovery-tool integration, full-stack E2E and provider certification are separate gates.
Frontend browser tests use the existing mock API, not the production backend. CI does not waive existing formatter,
lint or test failures; those must be fixed if the first clean run exposes them.

## Build once, deploy by digest

1. PR: validation + affected service tests/builds, no AWS permissions or publication.
2. Main push/manual CI: same checks; if enabled, package tested JARs using `backend/Dockerfile.ci` and push images.
3. Each image gets a commit/run/attempt tag and an immutable digest. Assemble `backend-release` only after required
   checks and every selected image publication succeed. Partial publication cannot produce a deployable release.
4. Automatic testing deployment is optional. Production deployment is a manual **OpenHire Deploy** run referencing
   the successful CI run ID; it promotes the same digests, not rebuilt binaries.
5. Deploy validates the CI run belongs to this repository, the exact CI workflow, successful main-branch push/manual
   execution and the matching SHA/run artifact. PR/fork runs are never trusted for privileged deployment.
6. Helm upgrades the existing release with `--reuse-values`, per-service image overrides, `--atomic` and `--wait`.
   Unchanged images and environment-specific values are retained. Chart changes can intentionally affect multiple services.
7. Rollout progress and a per-service summary appear in Actions. Failed Helm upgrades attempt atomic rollback; this does
   not undo database changes. A post-upgrade verification failure needs operator review, not a claimed database rollback.

All images currently target **linux/amd64**. Use compatible EKS nodes; ARM/Graviton needs a separately tested build target.
This pipeline deploys the **18 backend applications**. Frontend CI is included, but frontend hosting/deployment is not:
the SPA/auth/Next.js applications need a separately selected hosting topology and authentication origins.

## GitHub setup (no AWS deployment yet)

1. Review and commit `.github/`, backend/frontend source, Gradle wrapper and lockfiles. This checkout initially had only
   `.gitignore` tracked; untracked files do not run in GitHub. Do not commit local `.env`, credentials or build outputs.
2. Push to a GitHub repository with default branch `main`, enable Actions, and run **OpenHire CI** manually once.
3. Add **CI gate** as a required branch-protection check. Protect workflow changes with review/CODEOWNERS as appropriate.
4. No AWS configuration is required. Publication/deployment jobs are hard-disabled; keep CD variables unset for future safety.
5. Review reports and actual skipped tests. A locally validated workflow still needs its first real runner execution.

## AWS/EKS prerequisites before enabling CD

- Existing Hyderabad (`ap-south-2`) EKS cluster, network access and tested dependencies/storage. No cluster, database,
  bucket, ECR repository or production credential is created by these workflows.
- Existing ECR repositories `openhire/<service>` for each of the 18 services, with appropriate retention and scanning
  policies. Registry and deployment configuration must refer to the same approved account/prefix.
- Existing reviewed Helm release in each target namespace, with all service entries, production-safe values and a
  fallback global `image.tag`. Disabled workloads remain disabled; their image versions may be recorded for later use.
  Initial installation remains an explicit infrastructure/bootstrap step, not a hidden CI side effect.
- Existing Kubernetes Secrets named `<release>-<service>` for enabled workloads. These can be populated by External
  Secrets Operator; ESO/Secrets Manager provisioning is not implemented by this pipeline. No secret values enter GitHub.
- IAM OIDC trust for the specific repository and GitHub environments. The publish role needs scoped ECR push access;
  deployment roles need EKS discovery plus EKS access/RBAC limited to their release namespaces. No permanent AWS keys.
- EKS node role/image pull access to ECR. Rotate runtime secrets independently from CI roles.
- For private EKS endpoints, a trusted runner with VPC connectivity and AWS CLI v2 is required. Never expose the EKS
  API to all addresses or place untrusted PR jobs on a privileged deployment runner.

Create GitHub environments **image-publish**, **testing**, and **production**. Restrict deployments to `main` and
configure required reviewers, especially production; workflow YAML cannot configure reviewers for you. If your GitHub
plan does not support the required protection, do not enable automatic CD until an equivalent approval gate exists.

### Repository variables

| Variable | Value/purpose |
| --- | --- |
| `ENABLE_AWS_CD` | Future opt-in after workflow guards are restored; currently has no effect |
| `AUTO_DEPLOY_TESTING` | `true` permits testing deployment after successful main CI; default off |
| `EKS_RUNNER_JSON` | JSON labels, e.g. `["self-hosted","linux","eks-deploy"]`; default `["ubuntu-latest"]` |

`EKS_RUNNER_JSON` is a repository variable so runner selection is known before environment assignment. If you use
GitHub-hosted deployment runners, arrange controlled network access to the cluster; OIDC alone does not provide it.

### Environment variables (GitHub configuration variables, not secret values)

| Environment | Variables |
| --- | --- |
| `image-publish` | `AWS_PUBLISH_ROLE_ARN` |
| `testing` and `production` | `AWS_DEPLOY_ROLE_ARN`, `EKS_CLUSTER`, `K8S_NAMESPACE`, `HELM_RELEASE`, `ECR_PREFIX`, `KUBECTL_VERSION`; optional `APPLICATION_URL` |

Example `ECR_PREFIX` shape: `<account-id>.dkr.ecr.ap-south-2.amazonaws.com/openhire`.
Set `KUBECTL_VERSION` to an explicit version compatible with the cluster. Use distinct target namespaces/releases or
clusters and roles for testing and production. The pipeline is serialized per environment, not per service, to avoid
concurrent mutations of the same umbrella Helm release.

## First deployment, missed releases and rollback

The first pipeline deployment to an existing Helm release requires a **full manual CI build** because there is no
recorded baseline. It records the source SHA in Helm values (`ci.sourceSha`). Later deployments compare that baseline
to the proposed commit and refuse any release that omits affected images. Thus a skipped/failed/cancelled earlier run
cannot silently leave another service stale. Run full CI manually to recover or promote a cumulative release.

Out-of-order releases are rejected using Git ancestry. A manual application rollback is an explicit operator procedure
using Helm history/rollback and compatible database state; the automated pipeline does not deploy an older SHA as if it
were a new release. Reverting source on `main` and creating a new tested release is the preferred normal rollback path.
Chart defaults are retained by `--reuse-values`; deliberate environment/default migrations need a reviewed Helm update.

Artifact retention: JAR/test artifacts 14 days, image/release metadata 30 days. Expired release artifacts cannot be
promoted; build a new release. Preserve deployed ECR digests in image retention policy. GitHub-hosted minutes/artifact
storage, ECR and EKS are billable according to your account; these pipelines are not a free-hosting guarantee.

## Local verification without building Java or touching AWS

From the repository root, with Node and Helm available:

```sh
node --test .github/scripts/*.test.mjs backend/scripts/deploy-local.test.mjs backend/scripts/mongo-recovery.test.mjs
helm lint backend/infra/helm/openhire --set image.tag=offline-test
helm template openhire backend/infra/helm/openhire --set image.tag=offline-test
```

These tests cover all 18 service selectors, shared dependency propagation, docs/chart/frontend changes, artifact
validation, incomplete-release rejection and image preservation. They do not claim GitHub execution or live EKS acceptance.

Validation checkpoint (2026-10-17): 20 local Node tests passed, Helm lint/render and per-service digest checks passed,
and both workflows passed Actionlint 1.7.7. Java builds, GitHub runner jobs and AWS operations were not executed locally.

References: [GitHub pipeline visualization and logs](https://docs.github.com/en/actions/how-tos/monitor-workflows),
[OIDC authentication to AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws),
[Helm upgrade options](https://docs.helm.sh/docs/helm/helm_upgrade/).
