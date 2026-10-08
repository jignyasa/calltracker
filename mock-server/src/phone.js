'use strict';

// Minimal Indian-default normalizer for the mock. The app normalizes with libphonenumber
// before sending; this only has to agree with it for the common input shapes.
function normalizePhone(raw) {
  if (typeof raw !== 'string' && typeof raw !== 'number') return null;
  let s = String(raw).trim();
  const hasPlus = s.startsWith('+');
  let digits = s.replace(/\D/g, '');
  if (!digits) return null;

  if (!hasPlus) {
    if (digits.startsWith('00')) digits = digits.slice(2);
    else if (digits.length === 11 && digits.startsWith('0')) digits = '91' + digits.slice(1);
    else if (digits.length === 10) digits = '91' + digits;
  }
  const e164 = '+' + digits;
  return /^\+[1-9]\d{7,14}$/.test(e164) ? e164 : null;
}

module.exports = { normalizePhone };
