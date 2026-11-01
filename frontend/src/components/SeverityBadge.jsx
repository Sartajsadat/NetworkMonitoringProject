import { useI18n } from '../i18n/I18nContext.jsx';
import { SEVERITY_STYLE } from '../pages/events/eventText.js';

/** Severity with icon and word, so it is never shown by colour alone. */
export default function SeverityBadge({ severity }) {
  const { t } = useI18n();
  const s = SEVERITY_STYLE[severity] || SEVERITY_STYLE.INFO;
  return (
    <span className={`badge text-bg-${s.bg} ${s.bg === 'warning' || s.bg === 'info' ? 'text-dark' : ''} d-inline-flex align-items-center gap-1`}>
      <i className={`bi bi-${s.icon}`} aria-hidden="true" />{t(`severity.${severity}`)}
    </span>
  );
}
