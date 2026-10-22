// Cosmetic only. Authentication fields, form actions and tokens are managed exclusively by Keycloak.
document.addEventListener('DOMContentLoaded', () => {
  const icon = document.querySelector('link[rel="icon"]');
  if (icon) icon.href = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#6554c0"/><text x="16" y="23" text-anchor="middle" fill="white" font-family="sans-serif" font-size="22" font-weight="bold">O</text></svg>');
});
