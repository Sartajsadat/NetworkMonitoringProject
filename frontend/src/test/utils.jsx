import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext } from '../auth/AuthContext.jsx';
import { hasAnyRole } from '../auth/roles.js';
import { I18nProvider } from '../i18n/I18nContext.jsx';

export function makeUser(roles = ['READ_ONLY'], overrides = {}) {
  return { id: 'u1', username: 'tester', fullName: 'Test User', email: 't@example.gov', roles, ...overrides };
}

/** Renders `ui` inside a router and a fixed auth state (no network). */
export function renderWithAuth(ui, { user = makeUser(), route = '/', path = '*', auth = {}, lang = 'en' } = {}) {
  const value = {
    user,
    initializing: false,
    setupRequired: false,
    sessionExpired: false,
    login: async () => user,
    logout: async () => {},
    completeSetup: () => {},
    can: (roles) => hasAnyRole(user, roles),
    ...auth,
  };
  return render(
    <I18nProvider initial={lang}>
    <AuthContext.Provider value={value}>
      <MemoryRouter initialEntries={[route]}>
        <Routes>
          <Route path={path} element={ui} />
          <Route path="/login" element={<div>LOGIN PAGE</div>} />
          <Route path="/setup" element={<div>SETUP PAGE</div>} />
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>
    </I18nProvider>,
  );
}
