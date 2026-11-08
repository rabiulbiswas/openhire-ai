# Retired résumé antivirus scanner

ClamAV and its runtime integration have been removed from all deployment environments.
The application retains file validation, text extraction, OCR and authorized downloads.
New processing outcomes report `virusScan: NOT_PERFORMED`; they do not certify files as malware-free.

Deploy resume-processor normally. The old `--no-scan` flag and
`docker-compose.local-no-scan.yml` filename are inert compatibility shims.

An old scanner container can remain from an earlier Compose deployment. Verify its project and service labels,
then stop/remove only that obsolete container. Do not use `--remove-orphans` or prune unrelated containers.
Existing signature volumes are left untouched; volume deletion is a separate operator choice.

Historical scan outcomes and rejected records are not rewritten or automatically retried.
See [current resume processing](../../docs/services/resume-processor.md).
