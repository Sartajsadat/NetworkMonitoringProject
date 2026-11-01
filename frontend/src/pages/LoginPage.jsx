import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { useI18n } from '../i18n/I18nContext.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import AuthShell from './AuthShell.jsx';

export default function LoginPage() {
  const { user, login, initializing, setupRequired, sessionExpired } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (initializing) return <LoadingSpinner fullPage />;
  if (setupRequired) return <Navigate to="/setup" replace />;
  if (user) return <Navigate to={location.state?.from || '/'} replace />;

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      navigate(location.state?.from || '/', { replace: true });
    } catch (err) {
      setError(err);
      setPassword('');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AuthShell>
      <h1 className="h4 mb-1">{t('login.title')}</h1>
      <p className="text-body-secondary small mb-4">{t('app.authorizedOnly')}</p>
      {sessionExpired && !error && <div className="alert alert-warning small" role="status">{t('login.sessionExpired')}</div>}
      <ErrorAlert error={error} />
      <form onSubmit={submit} noValidate>
        <div className="mb-3">
          <label htmlFor="username" className="form-label">{t('login.username')}</label>
          <input id="username" className="form-control nm-ltr" dir="ltr" autoComplete="username" value={username}
            onChange={(e) => setUsername(e.target.value)} required autoFocus />
        </div>
        <div className="mb-3">
          <label htmlFor="password" className="form-label">{t('login.password')}</label>
          <input id="password" type="password" className="form-control nm-ltr" dir="ltr" autoComplete="current-password"
            value={password} onChange={(e) => setPassword(e.target.value)} required />
        </div>
        <button type="submit" className="btn btn-primary w-100" disabled={busy || !username || !password}>
          {busy && <span className="spinner-border spinner-border-sm me-2" aria-hidden="true" />}
          {t('login.submit')}
        </button>
      </form>
      <p className="small text-body-secondary mt-3 mb-0">{t('login.footer')}</p>
    </AuthShell>
  );
}
