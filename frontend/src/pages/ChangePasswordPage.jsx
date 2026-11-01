import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/endpoints.js';
import { setAccessToken } from '../api/client.js';
import { useAuth } from '../auth/AuthContext.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import PageHeader from '../components/PageHeader.jsx';
import PasswordHint from './PasswordHint.jsx';

export default function ChangePasswordPage() {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ currentPassword: '', newPassword: '', confirm: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    if (form.newPassword !== form.confirm) {
      setError('The new passwords do not match.');
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
    <div style={{ maxWidth: 520 }}>
      <PageHeader title="Change password" breadcrumbs={[{ label: 'Change password' }]}
        subtitle="Changing your password signs you out on every device." />
      <div className="card"><div className="card-body">
        <ErrorAlert error={error} />
        <form onSubmit={submit}>
          <div className="mb-3">
            <label htmlFor="cp-current" className="form-label">Current password</label>
            <input id="cp-current" type="password" className="form-control" value={form.currentPassword} onChange={set('currentPassword')} required autoComplete="current-password" />
          </div>
          <div className="mb-3">
            <label htmlFor="cp-new" className="form-label">New password</label>
            <input id="cp-new" type="password" className="form-control" value={form.newPassword} onChange={set('newPassword')} required autoComplete="new-password" />
            <PasswordHint />
          </div>
          <div className="mb-3">
            <label htmlFor="cp-confirm" className="form-label">Confirm new password</label>
            <input id="cp-confirm" type="password" className="form-control" value={form.confirm} onChange={set('confirm')} required autoComplete="new-password" />
          </div>
          <button type="submit" className="btn btn-primary" disabled={busy}>Change password</button>
        </form>
      </div></div>
    </div>
  );
}
