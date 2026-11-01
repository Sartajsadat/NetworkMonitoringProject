// Supported UI languages. `locale` drives Intl date/number formatting; Afghan locales use the
// Solar Hijri calendar and Eastern Arabic-Indic digits, which is what readers expect.
// `fallbackLocale` is used when the browser has no data for `locale` (Chrome ships no Pashto
// formatting data): same digits and calendar, and dates are then shown numerically so no
// month names from another language appear.
export const LANGUAGES = {
  en: { code: 'en', label: 'English', htmlLang: 'en', locale: 'en-GB', dir: 'ltr' },
  ps: { code: 'ps', label: 'پښتو', htmlLang: 'ps-AF', locale: 'ps-AF-u-nu-arabext', fallbackLocale: 'fa-AF-u-nu-arabext', dir: 'rtl' },
  fa: { code: 'fa', label: 'دری', htmlLang: 'fa-AF', locale: 'fa-AF-u-nu-arabext', dir: 'rtl' },
};

export const DEFAULT_LANGUAGE = 'en';
export const STORAGE_KEY = 'netmon.language';

/** Saved choice, else the browser's preferred language if supported, else English. */
export function initialLanguage() {
  try {
    const saved = window.localStorage.getItem(STORAGE_KEY);
    if (saved && LANGUAGES[saved]) return saved;
  } catch {
    // storage blocked: fall through
  }
  const browser = (typeof navigator !== 'undefined' && navigator.language ? navigator.language : '').toLowerCase();
  if (browser.startsWith('ps')) return 'ps';
  if (browser.startsWith('fa') || browser.startsWith('prs')) return 'fa';
  return DEFAULT_LANGUAGE;
}

export function saveLanguage(code) {
  try {
    window.localStorage.setItem(STORAGE_KEY, code);
  } catch {
    // ignore
  }
}
