import { useI18n } from './I18nContext.jsx';
import { LANGUAGES } from './languages.js';

/** Segmented language control. Each label is written in its own language so anyone can find theirs. */
export default function LanguageSwitcher({ variant = 'light', className = '' }) {
  const { lang, setLanguage, t } = useI18n();
  return (
    <div className={`btn-group btn-group-sm nm-lang ${className}`} role="group" aria-label={t('lang.label')}>
      {Object.values(LANGUAGES).map((l) => (
        <button
          key={l.code}
          type="button"
          lang={l.htmlLang}
          dir={l.dir}
          className={`btn ${lang === l.code ? `btn-${variant === 'onDark' ? 'light' : 'primary'}` : `btn-outline-${variant === 'onDark' ? 'light' : 'secondary'}`}`}
          aria-pressed={lang === l.code}
          onClick={() => setLanguage(l.code)}
        >
          {l.label}
        </button>
      ))}
    </div>
  );
}
