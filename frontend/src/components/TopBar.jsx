import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { useI18n } from '../i18n/I18nContext.jsx';
import LanguageSwitcher from '../i18n/LanguageSwitcher.jsx';

/** Green ministry band across the top of every signed-in page. */
export default function TopBar({ onToggleSidebar }) {
  const { user, logout } = useAuth();
  const { t, isRtl } = useI18n();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef(null);

  useEffect(() => {
    if (!menuOpen) return undefined;
    const close = (e) => {
      if (menuRef.current && !menuRef.current.contains(e.target)) setMenuOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [menuOpen]);

  const handleLogout = async () => {
    setMenuOpen(false);
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <header className="nm-band">
      <button type="button" className="btn btn-sm btn-band d-lg-none" onClick={onToggleSidebar} aria-label={t('topbar.toggleNav')}>
        <i className="bi bi-list" aria-hidden="true" />
      </button>
      <div className="nm-band-title d-none d-md-block">
        {t('app.ministry')}
        <small>{t('app.platform')}</small>
      </div>
      <div className="ms-auto d-flex align-items-center gap-2">
        <LanguageSwitcher variant="onDark" className="d-none d-sm-inline-flex" />
        <div className="position-relative" ref={menuRef}>
          <button type="button" className="btn btn-sm btn-band d-flex align-items-center gap-2"
            onClick={() => setMenuOpen((o) => !o)} aria-expanded={menuOpen} aria-haspopup="menu">
            <i className="bi bi-person-circle" aria-hidden="true" />
            <span>{user?.fullName}</span>
            <i className="bi bi-caret-down-fill small" aria-hidden="true" />
          </button>
          {menuOpen && (
            <div className="dropdown-menu show mt-1" style={{ insetInlineEnd: 0, insetInlineStart: 'auto', minWidth: '15rem' }} role="menu">
              <div className="px-3 py-1 small text-body-secondary">
                {t('topbar.signedInAs')} <strong className="nm-ltr">{user?.username}</strong>
                <div>{user?.roles?.map((r) => t(`role.${r}`)).join(isRtl ? '، ' : ', ')}</div>
              </div>
              <div className="px-3 py-2 d-sm-none"><LanguageSwitcher /></div>
              <div className="dropdown-divider" />
              <Link className="dropdown-item" to="/account/password" onClick={() => setMenuOpen(false)} role="menuitem">
                <i className="bi bi-key me-2" aria-hidden="true" />{t('topbar.changePassword')}
              </Link>
              <button type="button" className="dropdown-item" onClick={handleLogout} role="menuitem">
                <i className="bi bi-box-arrow-right me-2 nm-flip" aria-hidden="true" />{t('topbar.signOut')}
              </button>
            </div>
          )}
        </div>
      </div>
    </header>
  );
}
