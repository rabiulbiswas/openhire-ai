# Local UI login accounts

Run from the `openhire-ai` root with Node 22+ and local Keycloak running. This creates login identities, not sample jobs, resumes, employee records or Mongo member records. Existing development organization `demo-org` should already exist through identity-service setup.

First apply [the local login repair](LOCAL_LOGIN_REPAIR.md) to align callback URLs and organization/role claims in existing realms.

```bash
# Safe inventory only: no connection or changes
node scripts/setup/seed-ui-users.mjs

# Enter secrets interactively in the Mac's zsh (not shell history)
read -s 'KEYCLOAK_ADMIN_PASSWORD?Local Keycloak admin password: '; echo
read -s 'OPENHIRE_DEMO_PASSWORD?New demo password (12+ characters): '; echo
export KEYCLOAK_ADMIN_PASSWORD OPENHIRE_DEMO_PASSWORD
node scripts/setup/seed-ui-users.mjs --apply
unset KEYCLOAK_ADMIN_PASSWORD OPENHIRE_DEMO_PASSWORD
```

Default admin username is `admin`; override `KEYCLOAK_ADMIN_USERNAME` if needed. Default server is `http://localhost:8180`; `OPENHIRE_KEYCLOAK_URL` accepts loopback origins only. Do not use this against a production instance forwarded to localhost. All newly created users share the password you entered; it is never printed or committed.

| Login email | Role |
| --- | --- |
| demo.recruiter@openhire.local | Recruiter |
| demo.hr-admin@openhire.local | HR administrator |
| demo.hiring-manager@openhire.local | Hiring manager |
| demo.interviewer@openhire.local | Interviewer |
| demo.org-admin@openhire.local | Organization administrator |
| demo.compliance@openhire.local | Compliance officer |
| demo.super-admin@openhire.local | Super administrator |
| demo.agency@openhire.local | Agency recruiter |
| demo.employee@openhire.local | Employee |
| demo.candidate@openhire.local | Candidate |

All except candidate use `openhire-staff`; candidate uses `openhire-candidate`. Staff login starts at `http://localhost:5176/login`; the platform app is on port 5175 and agency portal on 5174. Candidate login starts at `/candidate-sign-in` on port 5176. Current role-routing/callback restrictions still apply: creating users does not fix invalid redirect URIs or unify realms.

Re-running preserves existing passwords/accounts. If an existing demo account is disabled or missing its expected role, the command stops for administrator review rather than granting privileges to an unknown account. If interrupted between account creation and role assignment, inspect that exact account in Keycloak and assign the intended role before retrying. The command does not recreate realms, weaken MFA/password policies or reset existing sessions.

Existing policy may require a stronger password or MFA enrollment. Role access does not imply sample business data exists: employees need onboarding records, agencies need assigned jobs, and candidates currently use application receipts for several portal workflows.

This setup is explicit and local-only, not automatic production startup seeding. Remove the `demo.*@openhire.local` accounts through Keycloak when no longer needed. No accounts were provisioned during implementation; run the command with your local admin credential.
