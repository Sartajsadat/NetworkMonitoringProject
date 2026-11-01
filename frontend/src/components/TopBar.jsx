import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { ROLE_LABELS } from '../auth/roles.js';

export default function TopBar({ onToggleSidebar }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);

  const handleLogout = async () => {
    setMenuOpen(false);
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <header className="navbar bg-body border-bottom px-3 py-2">
      <button type="button" className="btn btn-outline-secondary btn-sm d-lg-none" onClick={onToggleSidebar} aria-label="Toggle navigation">
        <i className="bi bi-list" aria-hidden="true" />
      </button>
      <span className="text-body-secondary small d-none d-md-inline">Ministry of Telecommunication · Infrastructure Monitoring</span>
      <div className="ms-auto position-relative">
        <button
          type="button"
          className="btn btn-sm btn-outline-secondary d-flex align-items-center gap-2"
          onClick={() => setMenuOpen((o) => !o)}
          aria-expanded={menuOpen}
          aria-haspopup="menu"
        >
          <i className="bi bi-person-circle" aria-hidden="true" />
          <span>{user?.fullName}</span>
          <i className="bi bi-caret-down-fill small" aria-hidden="true" />
        </button>
        {menuOpen && (
          <div className="dropdown-menu dropdown-menu-end show mt-1" style={{ right: 0, left: 'auto' }} role="menu">
            <div className="px-3 py-1 small text-body-secondary">
              Signed in as <strong>{user?.username}</strong>
              <div>{user?.roles?.map((r) => ROLE_LABELS[r] || r).join(', ')}</div>
            </div>
            <div className="dropdown-divider" />
            <Link className="dropdown-item" to="/account/password" onClick={() => setMenuOpen(false)} role="menuitem">
              <i className="bi bi-key me-2" aria-hidden="true" />Change password
            </Link>
            <button type="button" className="dropdown-item" onClick={handleLogout} role="menuitem">
              <i className="bi bi-box-arrow-right me-2" aria-hidden="true" />Sign out
            </button>
          </div>
        )}
      </div>
    </header>
  );
}
