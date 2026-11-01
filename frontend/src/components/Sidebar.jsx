import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { NAV_SECTIONS } from './navigation.js';

export default function Sidebar({ open, onNavigate }) {
  const { can } = useAuth();
  return (
    <nav className={`nm-sidebar ${open ? 'open' : ''}`} aria-label="Main navigation">
      <div className="nm-brand">
        <i className="bi bi-activity fs-5" aria-hidden="true" />
        <span>NetMon</span>
      </div>
      {NAV_SECTIONS.map((section) => {
        const items = section.items.filter((i) => !i.roles || can(i.roles));
        if (items.length === 0) return null;
        return (
          <div key={section.title}>
            <div className="nm-section">{section.title}</div>
            {items.map((item) =>
              item.phase ? (
                <span
                  key={item.label}
                  className="nm-link disabled"
                  aria-disabled="true"
                  title={`Not available yet - planned for Phase ${item.phase}`}
                >
                  <i className={`bi bi-${item.icon}`} aria-hidden="true" />
                  {item.label}
                  <span className="badge text-bg-secondary nm-phase">Phase {item.phase}</span>
                </span>
              ) : (
                <NavLink key={item.to} to={item.to} end={item.end} className="nm-link" onClick={onNavigate}>
                  <i className={`bi bi-${item.icon}`} aria-hidden="true" />
                  {item.label}
                </NavLink>
              ),
            )}
          </div>
        );
      })}
    </nav>
  );
}
