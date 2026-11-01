import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import en from './en.js';
import ps from './ps.js';
import fa from './fa.js';
import { LANGUAGES, initialLanguage, saveLanguage } from './languages.js';
import { makeFormatters } from '../utils/format.js';

const DICTIONARIES = { en, ps, fa };

const I18nContext = createContext(null);

/** Replaces {name} placeholders. Missing keys fall back to English, then to the key itself. */
export function translate(lang, key, vars) {
  const text = DICTIONARIES[lang]?.[key] ?? en[key] ?? key;
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (m, name) => (vars[name] !== undefined ? String(vars[name]) : m));
}

/** Points the <link id="bootstrap-css"> at the LTR or RTL Bootstrap build. */
function applyDocument(language, stylesheets) {
  const root = document.documentElement;
  root.setAttribute('lang', language.htmlLang);
  root.setAttribute('dir', language.dir);
  if (!stylesheets) return;
  let link = document.getElementById('bootstrap-css');
  if (!link) {
    link = document.createElement('link');
    link.id = 'bootstrap-css';
    link.rel = 'stylesheet';
    document.head.prepend(link);
  }
  const href = language.dir === 'rtl' ? stylesheets.rtl : stylesheets.ltr;
  if (link.getAttribute('href') !== href) link.setAttribute('href', href);
}

export function I18nProvider({ children, initial, stylesheets }) {
  const [lang, setLangState] = useState(() => initial || initialLanguage());
  const language = LANGUAGES[lang];

  useEffect(() => {
    applyDocument(language, stylesheets);
    document.title = `${translate(lang, 'app.name')} · ${translate(lang, 'app.platform')}`;
  }, [lang, language, stylesheets]);

  const setLanguage = useCallback((code) => {
    if (!LANGUAGES[code]) return;
    saveLanguage(code);
    setLangState(code);
  }, []);

  const value = useMemo(() => {
    const t = (key, vars) => translate(lang, key, vars);
    const supported = Intl.DateTimeFormat.supportedLocalesOf([language.locale]).length > 0;
    const locale = supported || !language.fallbackLocale ? language.locale : language.fallbackLocale;
    const numberFormat = new Intl.NumberFormat(locale);
    const dateTimeFormat = supported
      ? new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'medium' })
      : new Intl.DateTimeFormat(locale, {
        year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
      });
    const formatNumber = (n) => (n === null || n === undefined ? '' : numberFormat.format(n));
    const units = makeFormatters(locale, language.dir === 'rtl');
    const formatDuration = (seconds) => {
      if (seconds === null || seconds === undefined) return '—';
      const d = Math.floor(seconds / 86400);
      const h = Math.floor((seconds % 86400) / 3600);
      const m = Math.floor((seconds % 3600) / 60);
      if (d > 0) return t('time.daysHours', { d: formatNumber(d), h: formatNumber(h) });
      if (h > 0) return t('time.hoursMinutes', { h: formatNumber(h), m: formatNumber(m) });
      return t('time.minutes', { m: formatNumber(m) });
    };
    const timeShort = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hour12: false });
    const dateTimeShort = new Intl.DateTimeFormat(locale, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
    return {
      /** Monitoring unit formatters: bytes, bits (per second), percent, decimal, perSec. */
      units,
      formatDuration,
      formatAxisTime: (ms, long) => (long ? dateTimeShort : timeShort).format(new Date(ms)),
      lang,
      language,
      dir: language.dir,
      isRtl: language.dir === 'rtl',
      setLanguage,
      t,
      /** Counts and other numbers in the reader's digits. Never use for IPs, ports or IDs. */
      formatNumber,
      formatDateTime: (iso) => (iso ? dateTimeFormat.format(new Date(iso)) : ''),
      /** Past times only ("5 min ago"), from our own translations so every language is covered. */
      formatRelative: (iso, now = Date.now()) => {
        const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
        if (seconds < 60) return t('time.justNow');
        if (seconds < 3600) return t('time.minutesAgo', { n: formatNumber(Math.floor(seconds / 60)) });
        if (seconds < 86400) return t('time.hoursAgo', { n: formatNumber(Math.floor(seconds / 3600)) });
        return t('time.daysAgo', { n: formatNumber(Math.floor(seconds / 86400)) });
      },
    };
  }, [lang, language, setLanguage]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n() {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error('useI18n must be used inside <I18nProvider>');
  return ctx;
}

export { DICTIONARIES };
