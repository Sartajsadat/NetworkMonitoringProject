import { useState } from 'react';
import { userApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { useI18n } from '../i18n/I18nContext.jsx';
import ConfirmDialog from '../components/ConfirmDialog.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import Modal from '../components/Modal.jsx';
import PageHeader from '../components/PageHeader.jsx';
import Pagination from '../components/Pagination.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import TimeAgo from '../components/TimeAgo.jsx';
import PasswordHint from './PasswordHint.jsx';

const EMPTY = { username: '', email: '', fullName: '', password: '', roles: ['READ_ONLY'] };

function userState(u) {
  if (!u.active) return 'USER_INACTIVE';
  if (u.locked) return 'USER_LOCKED';
  return 'USER_ACTIVE';
}

export default function UsersPage() {
  const { user: me } = useAuth();
  const { t, isRtl } = useI18n();
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi(() => userApi.search({ search: query || undefined, page, size: 25 }), [query, page]);
  const { data: roles } = useApi(() => userApi.roles(), []);

  const [editing, setEditing] = useState(null); // null | 'new' | user
  const [form, setForm] = useState(EMPTY);
  const [formError, setFormError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(null); // { user, action }
  const [resetFor, setResetFor] = useState(null);
  const [newPassword, setNewPassword] = useState('');
  const [actionError, setActionError] = useState(null);

  const open = (u) => {
    setEditing(u || 'new');
    setForm(u ? { username: u.username, email: u.email, fullName: u.fullName, password: '', roles: u.roles } : EMPTY);
    setFormError(null);
  };
  const toggleRole = (code) => setForm({
    ...form,
    roles: form.roles.includes(code) ? form.roles.filter((r) => r !== code) : [...form.roles, code],
  });

  const save = async (e) => {
    e.preventDefault();
    if (form.roles.length === 0) {
      setFormError(t('users.selectRole'));
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      if (editing === 'new') await userApi.create(form);
      else await userApi.update(editing.id, { email: form.email, fullName: form.fullName, roles: form.roles });
      setEditing(null);
      reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setBusy(false);
    }
  };

  const runAction = async () => {
    const { user, action } = confirm;
    setBusy(true);
    setActionError(null);
    try {
      await userApi[action](user.id);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const doReset = async (e) => {
    e.preventDefault();
    setBusy(true);
    setFormError(null);
    try {
      await userApi.resetPassword(resetFor.id, newPassword);
      setResetFor(null);
      setNewPassword('');
      reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setBusy(false);
    }
  };

  const listSep = isRtl ? '، ' : ', ';

  return (
    <>
      <PageHeader title={t('users.title')} subtitle={t('users.subtitle')} breadcrumbs={[{ label: t('users.title') }]}
        actions={<button type="button" className="btn btn-primary btn-sm" onClick={() => open(null)}>
          <i className="bi bi-person-plus me-1" aria-hidden="true" />{t('users.new')}</button>} />

      <form className="d-flex gap-2 mb-3" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(search); }} role="search">
        <input className="form-control form-control-sm" style={{ maxWidth: 340 }} placeholder={t('users.searchPlaceholder')}
          aria-label={t('users.searchPlaceholder')} value={search} onChange={(e) => setSearch(e.target.value)} />
        <button type="submit" className="btn btn-sm btn-outline-secondary">{t('common.search')}</button>
      </form>

      <ErrorAlert error={error} onRetry={reload} />
      <ErrorAlert error={actionError} />
      {loading && !data && <LoadingSpinner />}
      {data && (
        <div className="card">
          <div className="table-responsive">
            <table className="table nm-table">
              <thead><tr>
                <th>{t('users.col.username')}</th><th>{t('users.col.name')}</th><th>{t('users.col.email')}</th>
                <th>{t('users.col.roles')}</th><th>{t('users.col.state')}</th><th>{t('users.col.lastLogin')}</th><th />
              </tr></thead>
              <tbody>
                {data.content.map((u) => (
                  <tr key={u.id}>
                    <td><span className="nm-mono nm-ltr">{u.username}</span>{u.id === me?.id && <span className="badge text-bg-light ms-1">{t('common.you')}</span>}</td>
                    <td>{u.fullName}</td>
                    <td className="small"><span className="nm-ltr">{u.email}</span></td>
                    <td className="small">{u.roles.map((r) => t(`role.${r}`)).join(listSep)}</td>
                    <td><StatusBadge status={userState(u)} /></td>
                    <td className="small"><TimeAgo value={u.lastLoginAt} /></td>
                    <td className="text-end text-nowrap">
                      <button type="button" className="btn btn-sm btn-outline-secondary me-1" onClick={() => open(u)} aria-label={t('users.editAria', { name: u.username })}>
                        <i className="bi bi-pencil" aria-hidden="true" />
                      </button>
                      <button type="button" className="btn btn-sm btn-outline-secondary me-1" onClick={() => { setResetFor(u); setFormError(null); }} aria-label={t('users.resetAria', { name: u.username })}>
                        <i className="bi bi-key" aria-hidden="true" />
                      </button>
                      {u.locked && (
                        <button type="button" className="btn btn-sm btn-outline-warning me-1" onClick={() => setConfirm({ user: u, action: 'unlock' })} aria-label={t('users.unlockAria', { name: u.username })}>
                          <i className="bi bi-unlock" aria-hidden="true" />
                        </button>
                      )}
                      {u.id !== me?.id && (u.active ? (
                        <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setConfirm({ user: u, action: 'deactivate' })} aria-label={t('users.deactivateAria', { name: u.username })}>
                          <i className="bi bi-person-dash" aria-hidden="true" />
                        </button>
                      ) : (
                        <button type="button" className="btn btn-sm btn-outline-success" onClick={() => setConfirm({ user: u, action: 'activate' })} aria-label={t('users.activateAria', { name: u.username })}>
                          <i className="bi bi-person-check" aria-hidden="true" />
                        </button>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="card-footer"><Pagination data={data} onPage={setPage} /></div>
        </div>
      )}

      <Modal show={Boolean(editing)} title={editing === 'new' ? t('users.modalNew') : t('users.modalEdit', { name: editing?.username || '' })} onClose={() => setEditing(null)}>
        <form onSubmit={save}>
          <ErrorAlert error={formError} />
          {editing === 'new' && (
            <div className="mb-3">
              <label htmlFor="u-username" className="form-label">{t('users.username')}</label>
              <input id="u-username" className="form-control nm-ltr" dir="ltr" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} required autoComplete="off" />
            </div>
          )}
          <div className="mb-3">
            <label htmlFor="u-fullName" className="form-label">{t('users.fullName')}</label>
            <input id="u-fullName" className="form-control" value={form.fullName} onChange={(e) => setForm({ ...form, fullName: e.target.value })} required />
          </div>
          <div className="mb-3">
            <label htmlFor="u-email" className="form-label">{t('users.email')}</label>
            <input id="u-email" type="email" className="form-control nm-ltr" dir="ltr" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} required />
          </div>
          {editing === 'new' && (
            <div className="mb-3">
              <label htmlFor="u-password" className="form-label">{t('users.initialPassword')}</label>
              <input id="u-password" type="password" className="form-control nm-ltr" dir="ltr" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required autoComplete="new-password" />
              <PasswordHint />
            </div>
          )}
          <fieldset className="mb-3">
            <legend className="form-label fs-6">{t('users.roles')}</legend>
            {(roles || []).map((r) => (
              <div className="form-check" key={r.code}>
                <input className="form-check-input" type="checkbox" id={`role-${r.code}`} checked={form.roles.includes(r.code)} onChange={() => toggleRole(r.code)} />
                <label className="form-check-label" htmlFor={`role-${r.code}`}>
                  {t(`role.${r.code}`)} <span className="d-block small text-body-secondary">{t(`roleDesc.${r.code}`)}</span>
                </label>
              </div>
            ))}
          </fieldset>
          <div className="d-flex justify-content-end gap-2">
            <button type="button" className="btn btn-outline-secondary" onClick={() => setEditing(null)}>{t('common.cancel')}</button>
            <button type="submit" className="btn btn-primary" disabled={busy}>{t('common.save')}</button>
          </div>
        </form>
      </Modal>

      <Modal show={Boolean(resetFor)} title={t('users.resetTitle', { name: resetFor?.username || '' })} onClose={() => setResetFor(null)}>
        <form onSubmit={doReset}>
          <ErrorAlert error={formError} />
          <p className="small text-body-secondary">{t('users.resetBody')}</p>
          <label htmlFor="reset-pw" className="form-label">{t('users.newPassword')}</label>
          <input id="reset-pw" type="password" className="form-control nm-ltr" dir="ltr" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} required autoComplete="new-password" />
          <PasswordHint />
          <div className="d-flex justify-content-end gap-2 mt-3">
            <button type="button" className="btn btn-outline-secondary" onClick={() => setResetFor(null)}>{t('common.cancel')}</button>
            <button type="submit" className="btn btn-danger" disabled={busy}>{t('users.resetSubmit')}</button>
          </div>
        </form>
      </Modal>

      <ConfirmDialog show={Boolean(confirm)} title={confirm && t(`users.${confirm.action}.title`)}
        confirmLabel={confirm && t(`users.${confirm.action}.label`)}
        confirmVariant={confirm?.action === 'deactivate' ? 'danger' : 'primary'}
        busy={busy} onConfirm={runAction} onCancel={() => setConfirm(null)}>
        <strong className="nm-ltr">{confirm?.user.username}</strong>: {confirm && t(`users.${confirm.action}.body`)}
      </ConfirmDialog>
    </>
  );
}
