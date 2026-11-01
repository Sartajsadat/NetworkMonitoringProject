import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { setupApi } from '../api/endpoints.js';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PasswordHint from './PasswordHint.jsx';

/**
 * First-run provisioning of the first SUPER_ADMIN. Requires the one-time setup token that
 * the backend printed to its own log (or BOOTSTRAP_SETUP_TOKEN). There are no default credentials.
 */
export default function SetupPage() {
  const { initializing, setupRequired, completeSetup } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ setupToken: '', username: '', email: '', fullName: '', password: '', confirm: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (initializing) return <LoadingSpinner fullPage />;
  if (!setupRequired) return <Navigate to="/login" replace />;

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    if (form.password !== form.confirm) {
      setError('The two passwords do not match.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const { confirm, ...body } = form; // eslint-disable-line no-unused-vars
      await setupApi.createAdmin(body);
      completeSetup();
      navigate('/login', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="nm-auth-page">
      <div className="card shadow-sm nm-auth-card" style={{ maxWidth: 520 }}>
        <div className="card-body p-4">
          <h1 className="h4">First-run setup</h1>
          <p className="text-body-secondary small">
            No user accounts exist yet. Create the first <strong>super administrator</strong>. The one-time setup token is
            printed in the backend log on startup (<code>docker compose logs backend</code>) or is the value of
            <code> BOOTSTRAP_SETUP_TOKEN</code>.
          </p>
          <ErrorAlert error={error} />
          <form onSubmit={submit}>
            <div className="mb-3">
              <label htmlFor="setupToken" className="form-label">Setup token</label>
              <input id="setupToken" className="form-control font-monospace" value={form.setupToken} onChange={set('setupToken')} required autoComplete="off" />
            </div>
            <div className="row g-3">
              <div className="col-sm-6">
                <label htmlFor="su-username" className="form-label">Username</label>
                <input id="su-username" className="form-control" value={form.username} onChange={set('username')} required autoComplete="username" />
              </div>
              <div className="col-sm-6">
                <label htmlFor="su-fullName" className="form-label">Full name</label>
                <input id="su-fullName" className="form-control" value={form.fullName} onChange={set('fullName')} required />
              </div>
              <div className="col-12">
                <label htmlFor="su-email" className="form-label">Email</label>
                <input id="su-email" type="email" className="form-control" value={form.email} onChange={set('email')} required />
              </div>
              <div className="col-sm-6">
                <label htmlFor="su-password" className="form-label">Password</label>
                <input id="su-password" type="password" className="form-control" value={form.password} onChange={set('password')} required autoComplete="new-password" />
              </div>
              <div className="col-sm-6">
                <label htmlFor="su-confirm" className="form-label">Confirm password</label>
                <input id="su-confirm" type="password" className="form-control" value={form.confirm} onChange={set('confirm')} required autoComplete="new-password" />
              </div>
            </div>
            <PasswordHint />
            <button type="submit" className="btn btn-primary w-100 mt-3" disabled={busy}>
              {busy && <span className="spinner-border spinner-border-sm me-2" aria-hidden="true" />}
              Create administrator
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
