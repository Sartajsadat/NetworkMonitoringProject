import { useState } from 'react';
import { Link } from 'react-router-dom';
import { appApi, serverApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Modal from '../../components/Modal.jsx';
import PageHeader from '../../components/PageHeader.jsx';

const KINDS = { LINUX: ['LINUX_USER'], WINDOWS: ['WINDOWS_PROGRAM', 'WINDOWS_SERVICE'] };

export function SupportBadge({ support }) {
  const { t } = useI18n();
  if (!support?.supported) {
    return <span className="badge text-bg-secondary d-inline-flex align-items-center gap-1"><i className="bi bi-slash-circle" aria-hidden="true" />{t('appAccess.unsupported')}</span>;
  }
  return support.enforcing
    ? <span className="badge text-bg-success d-inline-flex align-items-center gap-1"><i className="bi bi-shield-check" aria-hidden="true" />{t('appAccess.enforceable')}</span>
    : <span className="badge text-bg-info text-dark d-inline-flex align-items-center gap-1"><i className="bi bi-eye" aria-hidden="true" />{t('appAccess.dryRunOnly')}</span>;
}

export function IdentityText({ kind, value }) {
  const { t } = useI18n();
  return <span>{t(`appAccess.kind.${kind}`)}: <span className="nm-mono" dir="ltr">{value}</span></span>;
}

export function AppForm({ app, onClose, onSaved }) {
  const { t } = useI18n();
  const servers = useApi(() => serverApi.search({ size: 200, sort: 'name,asc' }), []);
  const [f, setF] = useState(app ? { serverId: app.serverId, name: app.name, description: app.description || '', owner: app.owner || '',
    identityKind: app.identityKind, identityValue: app.identityValue } : { serverId: '', name: '', description: '', owner: '', identityKind: '', identityValue: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const server = (servers.data?.content || []).find((s) => s.id === f.serverId);
  const kinds = server ? KINDS[server.osFamily] || [] : [];
  const set = (k) => (e) => setF((x) => ({ ...x, [k]: e.target.value }));
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = { ...f, description: f.description || null, owner: f.owner || null };
    try {
      onSaved(app ? await appApi.update(app.id, body) : await appApi.create(body));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  return (
    <Modal show size="lg" title={app ? t('appAccess.edit') : t('appAccess.add')} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
        <button type="submit" form="app-form" className="btn btn-primary" disabled={busy}>{t('common.save')}</button></>)}>
      <form id="app-form" onSubmit={save} className="row g-3">
        <div className="col-md-6"><label className="form-label" htmlFor="ap-server">{t('fw.server')}</label>
          <select id="ap-server" className="form-select" required disabled={Boolean(app)} value={f.serverId}
            onChange={(e) => setF((x) => ({ ...x, serverId: e.target.value, identityKind: '' }))}>
            <option value="">{t('fw.chooseServer')}</option>
            {(servers.data?.content || []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select></div>
        <div className="col-md-6"><label className="form-label" htmlFor="ap-name">{t('appAccess.name')}</label>
          <input id="ap-name" className="form-control" required maxLength={120} value={f.name} onChange={set('name')} placeholder={t('appAccess.nameHint')} /></div>
        <div className="col-12"><div className="alert alert-light border small mb-0">{t('appAccess.identityExplain')}</div></div>
        <div className="col-md-5"><label className="form-label" htmlFor="ap-kind">{t('appAccess.identityKind')}</label>
          <select id="ap-kind" className="form-select" required value={f.identityKind} onChange={set('identityKind')} disabled={!server}>
            <option value="">{server ? t('appAccess.chooseKind') : t('appAccess.chooseServerFirst')}</option>
            {kinds.map((k) => <option key={k} value={k}>{t(`appAccess.kind.${k}`)}</option>)}
          </select></div>
        <div className="col-md-7"><label className="form-label" htmlFor="ap-value">{t('appAccess.identityValue')}</label>
          <input id="ap-value" className="form-control nm-ltr" dir="ltr" required maxLength={260} value={f.identityValue} onChange={set('identityValue')}
            placeholder={f.identityKind ? t(`appAccess.example.${f.identityKind}`) : ''} />
          {f.identityKind && <div className="form-text">{t(`appAccess.help.${f.identityKind}`)}</div>}</div>
        <div className="col-md-6"><label className="form-label" htmlFor="ap-owner">{t('appAccess.owner')}</label>
          <input id="ap-owner" className="form-control" maxLength={120} value={f.owner} onChange={set('owner')} /></div>
        <div className="col-md-6"><label className="form-label" htmlFor="ap-desc">{t('subnets.description')}</label>
          <input id="ap-desc" className="form-control" maxLength={500} value={f.description} onChange={set('description')} /></div>
        <div className="col-12"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}

export default function AppAccessPage() {
  const { t, formatNumber } = useI18n();
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageFirewall);
  const { data, error, loading, reload } = useApi(() => appApi.list(), []);
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);
  const [deleteError, setDeleteError] = useState(null);
  const remove = async () => {
    setBusy(true);
    setDeleteError(null);
    try { await appApi.remove(deleting.id); setDeleting(null); reload(); } catch (e) { setDeleteError(e); setDeleting(null); } finally { setBusy(false); }
  };
  return (
    <>
      <PageHeader title={t('appAccess.title')} subtitle={t('appAccess.subtitle')} breadcrumbs={[{ label: t('appAccess.title') }]}
        actions={canManage && <button type="button" className="btn btn-sm btn-primary" onClick={() => setAdding(true)}>
          <i className="bi bi-plus-lg me-1" aria-hidden="true" />{t('appAccess.add')}</button>} />
      <div className="alert alert-light border small"><i className="bi bi-info-circle me-1" aria-hidden="true" />{t('appAccess.explain')}</div>
      <ErrorAlert error={error} onRetry={reload} />
      <ErrorAlert error={deleteError} />
      {!data && loading && <LoadingSpinner />}
      {data && data.length === 0 && <div className="card"><EmptyState icon="app-indicator" title={t('appAccess.none')}>{t('appAccess.noneHint')}</EmptyState></div>}
      {data && data.length > 0 && (
        <div className="card"><div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr><th>{t('appAccess.application')}</th><th>{t('fw.server')}</th><th>{t('appAccess.identity')}</th><th>{t('appAccess.support')}</th>
              <th>{t('appAccess.policies')}</th>{canManage && <th />}</tr></thead>
            <tbody>
              {data.map((a) => (
                <tr key={a.id}>
                  <td className="fw-semibold"><Link to={`/app-access/${a.id}`}>{a.name}</Link>
                    {a.owner && <div className="small text-body-secondary fw-normal">{a.owner}</div>}</td>
                  <td><Link to={`/servers/${a.serverId}`}>{a.serverName}</Link></td>
                  <td className="small"><IdentityText kind={a.identityKind} value={a.identityValue} />
                    <div className="text-body-secondary">{a.seenProcesses?.length ? t('appAccess.seenRunning', { list: a.seenProcesses.slice(0, 3).join(', ') }) : t('appAccess.notSeen')}</div></td>
                  <td><SupportBadge support={a.support} />{!a.support?.supported && <div className="small text-body-secondary">{a.support?.reason}</div>}</td>
                  <td>{formatNumber(a.activePolicies)}</td>
                  {canManage && <td><button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDeleting(a)} aria-label={t('common.delete')}>
                    <i className="bi bi-trash" aria-hidden="true" /></button></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div></div>
      )}
      {adding && <AppForm onClose={() => setAdding(false)} onSaved={() => { setAdding(false); reload(); }} />}
      <ConfirmDialog show={Boolean(deleting)} title={t('appAccess.deleteTitle')} confirmLabel={t('common.delete')} busy={busy}
        onConfirm={remove} onCancel={() => setDeleting(null)}>{t('appAccess.deleteText', { name: deleting?.name || '' })}</ConfirmDialog>
    </>
  );
}
