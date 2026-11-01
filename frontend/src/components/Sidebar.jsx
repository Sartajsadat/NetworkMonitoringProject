import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { useI18n } from '../i18n/I18nContext.jsx';
import { NAV_SECTIONS } from './navigation.js';

export default function Sidebar({ open, onNavigate }) {
  const { can } = useAuth();
  const { t, formatNumber } = useI18n();
  return (
    <nav className={`nm-sidebar ${open ? 'open' : ''}`} aria-label={t('nav.main')}>
      <div className="nm-brand">
        <span className="nm-brand-mark" aria-hidden="true"><i className="bi bi-activity" /></span>
        <span>{t('app.name')}<small>{t('app.platform')}</small></span>
      </div>
      {NAV_SECTIONS.map((section) => {
        const items = section.items.filter((i) => !i.roles || can(i.roles));
        if (items.length === 0) return null;
        return (
          <div key={section.title}>
            <div className="nm-section">{t(section.title)}</div>
            {items.map((item) =>
              item.phase ? (
                <span key={item.label} className="nm-link disabled" aria-disabled="true"
                  title={t('nav.notAvailable', { n: formatNumber(item.phase) })}>
                  <i className={`bi bi-${item.icon}`} aria-hidden="true" />
                  {t(item.label)}
                  <span className="nm-phase">{t('nav.phase', { n: formatNumber(item.phase) })}</span>
                </span>
              ) : (
                <NavLink key={item.to} to={item.to} end={item.end} className="nm-link" onClick={onNavigate}>
                  <i className={`bi bi-${item.icon}`} aria-hidden="true" />
                  {t(item.label)}
                </NavLink>
              ),
            )}
          </div>
        );
      })}
    </nav>
  );
}
