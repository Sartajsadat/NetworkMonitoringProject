import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/endpoints.js';
import { setAccessToken } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { useI18n } from '../i18n/I18nContext.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import PageHeader from '../components/PageHeader.jsx';
import PasswordHint from './PasswordHint.jsx';

export default function ChangePasswordPage() {
  const { logout } = useAuth();
  const { t } = useI18n();
  const navigate = useNavigate();
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    if (form.newPassword !== form.confirm) {
      setError(t('changePassword.mismatch'));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await authApi.changePassword(form.currentPassword, form.newPassword);
      // Every session (including this one) is revoked by the backend.
      setAccessToken(null);
      await logout().catch(() => {});
      navigate('/login', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ maxWidth: 560 }}>
      <PageHeader title={t('changePassword.title')} breadcrumbs={[{ label: t('changePassword.title') }]} subtitle={t('changePassword.subtitle')} />
      <div className="card"><div className="card-body">
        <ErrorAlert error={error} />
        <form onSubmit={submit}>
          <div className="mb-3">
            <label htmlFor="cp-current" className="form-label">{t('changePassword.current')}</label>
            <input id="cp-current" type="password" className="form-control nm-ltr" dir="ltr" value={form.currentPassword} onChange={set('currentPassword')} required autoComplete="current-password" />
          </div>
          <div className="mb-3">
            <label htmlFor="cp-new" className="form-label">{t('changePassword.new')}</label>
            <input id="cp-new" type="password" className="form-control nm-ltr" dir="ltr" value={form.newPassword} onChange={set('newPassword')} required autoComplete="new-password" />
            <PasswordHint />
          </div>
          <div className="mb-3">
            <label htmlFor="cp-confirm" className="form-label">{t('changePassword.confirm')}</label>
            <input id="cp-confirm" type="password" className="form-control nm-ltr" dir="ltr" value={form.confirm} onChange={set('confirm')} required autoComplete="new-password" />
          </div>
          <button type="submit" className="btn btn-primary" disabled={busy}>{t('changePassword.submit')}</button>
        </form>
      </div></div>
    </div>
  );
}
