// Cosmetic only. Authentication fields, form actions and tokens are managed exclusively by Keycloak.
document.addEventListener('DOMContentLoaded', () => {
  const icon = document.querySelector('link[rel="icon"]');
  if (icon) icon.href = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="8" fill="#6554c0"/><text x="16" y="23" text-anchor="middle" fill="white" font-family="sans-serif" font-size="22" font-weight="bold">O</text></svg>');

  // Move the existing recovery link alongside its password label; preserve its server-generated URL.
  const password = document.getElementById('password');
  const passwordGroup = password?.closest('.pf-v5-c-form__group');
  const recovery = passwordGroup?.querySelector('a');
  const passwordLabel = passwordGroup?.querySelector('.pf-v5-c-form__group-label');
  if (recovery && passwordLabel) {
    passwordLabel.append(recovery);
    passwordLabel.style.display = 'flex';
    passwordLabel.style.justifyContent = 'space-between';
    passwordLabel.style.gap = '12px';
  }

  // One real input keeps screen-reader, autofill, paste and form-post behavior intact.
  // Six decorative cells mirror it; verification and redirects remain entirely server-owned.
  const otp = document.querySelector('#kc-otp-login-form input[name="otp"]');
  if (!(otp instanceof HTMLInputElement)) return;
  const control = otp.closest('.pf-v5-c-form-control');
  if (!control || control.closest('.oh-otp')) return;
  const cells = document.createElement('div');
  cells.className = 'oh-otp' + (otp.getAttribute('aria-invalid') === 'true' ? ' oh-otp-error' : '');
  control.before(cells);
  cells.append(control);
  const digits = Array.from({ length: 6 }, () => {
    const digit = document.createElement('span');
    digit.className = 'oh-otp-digit';
    digit.setAttribute('aria-hidden', 'true');
    cells.append(digit);
    return digit;
  });
  otp.maxLength = 6;
  otp.inputMode = 'numeric';
  otp.autocomplete = 'one-time-code';
  otp.pattern = '[0-9]{6}';
  otp.required = true;
  const refresh = () => {
    const caret = Math.min(otp.selectionStart ?? otp.value.length, 5);
    digits.forEach((digit, index) => {
      digit.textContent = otp.value[index] || '';
      digit.dataset.active = String(index === caret);
    });
  };
  otp.addEventListener('input', () => {
    const caret = otp.selectionStart ?? otp.value.length;
    const value = otp.value.replace(/\D/g, '').slice(0, 6);
    if (value !== otp.value) { otp.value = value; otp.setSelectionRange(Math.min(caret, value.length), Math.min(caret, value.length)); }
    refresh();
  });
  otp.addEventListener('focus', refresh);
  otp.addEventListener('keyup', refresh);
  otp.addEventListener('click', event => {
    const bounds = cells.getBoundingClientRect();
    const index = Math.max(0, Math.min(otp.value.length, Math.floor((event.clientX - bounds.left) / (bounds.width / 6))));
    otp.setSelectionRange(index, index);
    refresh();
  });
  if (otp.autofocus) otp.focus();
  refresh();
});
