# Local ClamAV lifecycle

## Optional local testing without antivirus

The explicit `docker-compose.local-no-scan.yml` override sets
`RESUME_SCAN_ENABLED=false` and activates `local-no-scan`. The normal configuration
still defaults to scanning enabled. The application refuses disabled scanning
without that local profile, or when any non-local profile is also active. Do not
deploy the local profile to production; this guard is configuration-based, not a
way to detect where a container is hosted.

Only use trusted synthetic files: file validation and extraction still run, but
malware detection does not. Records show `virusScan: SKIPPED_LOCAL_DEV`, not
`PASSED`. This permits unscanned files to proceed through the local pipeline.

After building the updated resume processor, run from the repository root:

```bash
docker compose -f docker-compose.yml -f docker-compose.local-no-scan.yml --profile phase3 up -d --no-deps --force-recreate resume-processor
docker compose --profile phase3 stop clamav
```

For a build and startup together, use the same two `-f` arguments with
`--profile phase3 up -d --build resume-processor` (requires Compose 2.24.4+).
The deployment script loads this override when passed `--no-scan`:
`./scripts/deployment/deploy-local.sh --no-scan --follow resume-processor`.
It stops ClamAV after the selected resume processor passes startup checks and
continues combined logs in the same console. Omitting `--no-scan` restores
the normal scanning configuration when deploying resume-processor. Existing failed uploads are not automatically
reprocessed by this change. To restore scanning, recreate the resume processor
with only `docker-compose.yml` and start ClamAV; previously skipped scans are
not retroactively certified as clean.

ClamAV scans uploaded resumes before parsing. An unavailable scanner must fail
closed; do not disable scanning to work around startup failures.

`start-local.sh` bootstraps missing main/daily signature databases, removes stale
Unix sockets, and invokes the installed image's `/init --foreground` path. The
upstream initializer still prepares clamd configuration and runs it under tini.
When clamd exits (including an OOM kill), the container exits and the existing
`restart: unless-stopped` policy can recover it. The default upstream startup
instead leaves `tail` running after the scanner dies, keeping an unhealthy
container alive indefinitely. Health checks still require a real scanner PING.

This wrapper targets the configured `clamav/clamav:1.4` image; review `/init`
compatibility before overriding the image. It intentionally runs only clamd,
not FreshClam or milter daemons. Initial signature download requires network
access. Existing signatures are preserved in `clamav-data`.

From the backend repository root, apply only this container change (no Java
builds or other service restarts):

```bash
docker compose --profile phase3 up -d --no-deps --force-recreate clamav
docker compose --profile phase3 logs --tail=80 -f clamav
# In a second terminal; allow time for the signature database to load:
docker compose --profile phase3 exec clamav clamdcheck.sh
```

The 1800 MB ceiling and non-concurrent database reload are unchanged. Restart
recovery does not create RAM: if OOM kills recur, run fewer service profiles or
stop explicitly selected unused services. Do not delete the signature volume.
An unhealthy but still-running daemon is not automatically restarted by Docker.

## Signature maintenance

Background signature updates were already disabled locally and remain disabled.
Cached/image signatures are not guaranteed current. Before testing real uploads,
update signatures with clamd stopped so two database copies do not compete for
memory (uploads cannot be scanned during this maintenance window):

```bash
docker compose --profile phase3 stop clamav
docker compose --profile phase3 run --rm --no-deps --entrypoint freshclam clamav --foreground --stdout
docker compose --profile phase3 up -d --no-deps clamav
docker compose --profile phase3 exec clamav clamdcheck.sh
```

If the update fails, investigate its error and restore scanner availability with
the last two commands; do not claim signatures are current. A production setup
needs scheduled updates, resource sizing and alerts for stale signatures/scanner
failures. This local fix is not a production antivirus maintenance strategy.
