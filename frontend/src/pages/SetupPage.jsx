import { useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import { useI18n } from '../i18n/I18nContext.jsx';
import { setupApi } from '../api/endpoints.js';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import AuthShell from './AuthShell.jsx';
import PasswordHint from './PasswordHint.jsx';

/**
 * First-run provisioning of the first SUPER_ADMIN. Requires the one-time setup token that
 * the backend printed to its own log (or BOOTSTRAP_SETUP_TOKEN). There are no default credentials.
 */
export default function SetupPage() {
  const { initializing, setupRequired, completeSetup } = useAuth();
  const { t } = useI18n();
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
      setError(t('setup.mismatch'));
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
    <AuthShell wide>
      <h1 className="h4">{t('setup.title')}</h1>
      <p className="text-body-secondary small">{t('setup.intro')}</p>
      <ErrorAlert error={error} />
      <form onSubmit={submit}>
        <div className="mb-3">
          <label htmlFor="setupToken" className="form-label">{t('setup.token')}</label>
          <input id="setupToken" className="form-control nm-mono nm-ltr" dir="ltr" value={form.setupToken} onChange={set('setupToken')} required autoComplete="off" />
        </div>
        <div className="row g-3">
          <div className="col-sm-6">
            <label htmlFor="su-username" className="form-label">{t('setup.username')}</label>
            <input id="su-username" className="form-control nm-ltr" dir="ltr" value={form.username} onChange={set('username')} required autoComplete="username" />
          </div>
          <div className="col-sm-6">
            <label htmlFor="su-fullName" className="form-label">{t('setup.fullName')}</label>
            <input id="su-fullName" className="form-control" value={form.fullName} onChange={set('fullName')} required />
          </div>
          <div className="col-12">
            <label htmlFor="su-email" className="form-label">{t('setup.email')}</label>
            <input id="su-email" type="email" className="form-control nm-ltr" dir="ltr" value={form.email} onChange={set('email')} required />
          </div>
          <div className="col-sm-6">
            <label htmlFor="su-password" className="form-label">{t('setup.password')}</label>
            <input id="su-password" type="password" className="form-control nm-ltr" dir="ltr" value={form.password} onChange={set('password')} required autoComplete="new-password" />
          </div>
          <div className="col-sm-6">
            <label htmlFor="su-confirm" className="form-label">{t('setup.confirm')}</label>
            <input id="su-confirm" type="password" className="form-control nm-ltr" dir="ltr" value={form.confirm} onChange={set('confirm')} required autoComplete="new-password" />
          </div>
        </div>
        <PasswordHint />
        <button type="submit" className="btn btn-primary w-100 mt-3" disabled={busy}>
          {busy && <span className="spinner-border spinner-border-sm me-2" aria-hidden="true" />}
          {t('setup.submit')}
        </button>
      </form>
    </AuthShell>
  );
}
