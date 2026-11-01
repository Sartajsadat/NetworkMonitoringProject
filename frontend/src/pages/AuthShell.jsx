import { useI18n } from '../i18n/I18nContext.jsx';
import LanguageSwitcher from '../i18n/LanguageSwitcher.jsx';

/** Layout for sign-in and first-run setup: ministry band with language choice, centred card. */
export default function AuthShell({ children, wide = false }) {
  const { t } = useI18n();
  return (
    <div className="nm-auth-page">
      <header className="nm-auth-band">
        <div className="d-flex align-items-center gap-2">
          <span className="nm-brand-mark" aria-hidden="true"><i className="bi bi-activity" /></span>
          <div className="nm-band-title">{t('app.ministry')}<small>{t('app.platform')}</small></div>
        </div>
        <LanguageSwitcher variant="onDark" />
      </header>
      <main className="nm-auth-body">
        <div className="card shadow-sm nm-auth-card" style={wide ? { maxWidth: 560 } : undefined}>
          <div className="card-body p-4">{children}</div>
        </div>
      </main>
    </div>
  );
}
