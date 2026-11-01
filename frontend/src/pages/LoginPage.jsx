import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';

export default function LoginPage() {
  const { user, login, initializing, setupRequired, sessionExpired } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (initializing) return <LoadingSpinner label="Checking session…" fullPage />;
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
    <div className="nm-auth-page">
      <div className="card shadow-sm nm-auth-card">
        <div className="card-body p-4">
          <div className="text-center mb-4">
            <i className="bi bi-activity fs-1 text-primary" aria-hidden="true" />
            <h1 className="h4 mt-2 mb-0">Infrastructure Monitoring</h1>
            <div className="text-body-secondary small">Ministry of Telecommunication · Authorized personnel only</div>
          </div>
          {sessionExpired && !error && (
            <div className="alert alert-warning small" role="status">Your session expired. Please sign in again.</div>
          )}
          <ErrorAlert error={error} />
          <form onSubmit={submit} noValidate>
            <div className="mb-3">
              <label htmlFor="username" className="form-label">Username</label>
              <input
                id="username"
                className="form-control"
                autoComplete="username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                autoFocus
              />
            </div>
            <div className="mb-3">
              <label htmlFor="password" className="form-label">Password</label>
              <input
                id="password"
                type="password"
                className="form-control"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </div>
            <button type="submit" className="btn btn-primary w-100" disabled={busy || !username || !password}>
              {busy && <span className="spinner-border spinner-border-sm me-2" aria-hidden="true" />}
              Sign in
            </button>
          </form>
          <p className="small text-body-secondary mt-3 mb-0">
            All access is logged. Accounts lock temporarily after repeated failed sign-ins.
          </p>
        </div>
      </div>
    </div>
  );
}
