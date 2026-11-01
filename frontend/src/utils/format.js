// Unit formatting for monitoring values. Numbers use the reader's digits (via formatNumber from
// useI18n); unit abbreviations (KB, Mb/s) stay in Latin script as is common in all three languages.

const BYTE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
const BIT_UNITS = ['b/s', 'Kb/s', 'Mb/s', 'Gb/s', 'Tb/s'];

function scaled(value, units, base) {
  let v = Math.abs(value);
  let i = 0;
  while (v >= base && i < units.length - 1) {
    v /= base;
    i += 1;
  }
  return { v: Math.sign(value) * v, unit: units[i] };
}

function digits(v) {
  return Math.abs(v) >= 100 ? 0 : Math.abs(v) >= 10 ? 1 : 2;
}

// Unicode "left-to-right isolate" marks (invisible). In Pashto/Dari text a value such as "1.4 GB" is
// otherwise reordered by the bidi algorithm; isolating it keeps number and unit together.
const LRI = '\u2066';
const PDI = '\u2069';

export function makeFormatters(intlLocale, rtl = false) {
  const num = (v, maxFrac) => new Intl.NumberFormat(intlLocale, { maximumFractionDigits: maxFrac }).format(v);
  const nil = (v) => v === null || v === undefined || Number.isNaN(v);
  const iso = (fns) => (rtl
    ? Object.fromEntries(Object.entries(fns).map(([k, fn]) => [k, (...a) => { const out = fn(...a); return out === '—' ? out : LRI + out + PDI; }]))
    : fns);
  return iso({
    bytes: (v) => {
      if (nil(v)) return '—';
      const s = scaled(v, BYTE_UNITS, 1024);
      return `${num(s.v, digits(s.v))} ${s.unit}`;
    },
    bytesPerSec: (v) => {
      if (nil(v)) return '—';
      const s = scaled(v, BYTE_UNITS, 1024);
      return `${num(s.v, digits(s.v))} ${s.unit}/s`;
    },
    bits: (v) => {
      if (nil(v)) return '—';
      const s = scaled(v, BIT_UNITS, 1000);
      return `${num(s.v, digits(s.v))} ${s.unit}`;
    },
    percent: (v, maxFrac = 1) => (nil(v) ? '—' : new Intl.NumberFormat(intlLocale, { style: 'percent', maximumFractionDigits: maxFrac }).format(v / 100)),
    decimal: (v, maxFrac = 2) => (nil(v) ? '—' : num(v, maxFrac)),
    perSec: (v) => (nil(v) ? '—' : `${num(v, v >= 100 ? 0 : 2)}/s`),
  });
}

/** Unit name returned by the API -> formatter key. */
export const UNIT_FORMAT = {
  percent: 'percent',
  bytes: 'bytes',
  bytes_per_second: 'bytesPerSec',
  bits_per_second: 'bits',
  packets_per_second: 'perSec',
  per_second: 'perSec',
  load: 'decimal',
};

/** Bootstrap colour for a utilisation percentage. */
export function levelFor(percent) {
  if (percent === null || percent === undefined) return 'secondary';
  if (percent >= 90) return 'danger';
  if (percent >= 75) return 'warning';
  return 'success';
}
