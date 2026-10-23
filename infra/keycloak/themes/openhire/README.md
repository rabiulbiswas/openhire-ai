# OpenHire authentication theme

The native Keycloak login, authenticator-code and forgot-password screens follow the attached OpenHire HTML
mockup: dark two-column layout, Schibsted Grotesk typography, brand rail, purple controls and the example hiring
summary. On mobile the brand header stays above the form and the large sidebar content is hidden.

Install using `bash scripts/setup/install-login-theme.sh`, then select `openhire` as the **Login theme** for
`openhire-platform` and `openhire-tenant` in `openhire-staff`. Installation updates files in the existing
container. It does not change callback URLs, account credentials, MFA policy, realm settings or client selection.

## Authentication behavior

- Forms keep Keycloak-generated actions, field names, hidden credentials, error messages and passkey hooks.
  Passwords and authenticator codes are posted directly to Keycloak.
- Social sign-in buttons appear above the email form only for configured identity providers. There are no
  placeholder Google/Microsoft/SSO buttons. Display names come from Keycloak's identity-provider settings.
- Remember-me appears only if enabled in the realm; this theme does not lengthen session lifetimes.
- The OTP screen requires a user with an enrolled authenticator and a browser flow that requests OTP.
  The current six-cell presentation matches the existing six-digit realm policy. If the OTP digit policy changes,
  update the presentation, title and numeric-input constraints together. Each code is validated by Keycloak;
  the BFF success handler selects the permitted dashboard afterward. There is no client-side verification stub.
- A single accessible `otp` input supplies six visual cells, preserving autofill, paste, keyboard editing and
  standard form submission. Without JavaScript it remains a standard numeric-input field.
- The mockup's “trust this device for 30 days” control is omitted: it requires a server-side trusted-device
  implementation. Alternative authentication/recovery is offered by Keycloak only when configured for that user.
- Password reset uses Keycloak's configured email flow. Its expiry comes from realm policy, so the UI does not
  promise an arbitrary expiry. Configure SMTP before expecting email delivery.
- Candidate/staff selectors, browse-demo links and self-service company creation are omitted from the unified
  tenant login. Tenant onboarding remains a Super Admin operation.

## Maintenance and verification

The customized `template.ftl`, `login.ftl`, `login-otp.ftl` and `login-reset-password.ftl` are derived from the
installed **Keycloak 26.8.0** templates. Review upstream template changes when upgrading Keycloak. Parent theme
`keycloak.v2` supplies shared field/button macros and authentication scripts. Upstream Keycloak source is
licensed under Apache-2.0: https://github.com/keycloak/keycloak/tree/26.8.0/themes . Schibsted Grotesk is bundled
from the frontend's installed Fontsource package, with its font license in `login/resources/fonts/LICENSE`.

The three templates were rendered with FreeMarker 2.3.35 and synthetic view data and visually inspected.
OTP input checks cover numeric entry, six cells, native form submission, autofill attributes and server-error
presentation. These are theme checks; successful/failed OTP validation, MFA enrollment, email delivery and
dashboard redirects still require acceptance against the running identity provider with a test account.
