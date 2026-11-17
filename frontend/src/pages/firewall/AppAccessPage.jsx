import { useEffect, useState } from 'react';
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
/** States in which the server has not answered yet: the page refreshes itself until it has. */
export const PENDING_STATES = ['TURNING_OFF', 'TURNING_ON'];
const STATE_STYLE = {
  ON: ['text-success', 'globe2'],
  OFF: ['text-danger', 'slash-circle'],
  OFF_TEST: ['text-warning-emphasis', 'eye'],
  TURNING_OFF: ['text-body-secondary', 'hourglass-split'],
  TURNING_ON: ['text-body-secondary', 'hourglass-split'],
  FAILED: ['text-danger', 'exclamation-triangle'],
  WAITING_APPROVAL: ['text-body-secondary', 'clock'],
  CUSTOM: ['text-body-secondary', 'sliders'],
};

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

/**
 * The on/off switch for one application. On = internet allowed. Turning it off asks for a
 * confirmation; the state text always shows what the server has really confirmed.
 */
export function InternetSwitch({ app, onChanged }) {
  const { t } = useI18n();
  const { can } = useAuth();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const state = app.internet?.state || 'ON';
  const allowed = state === 'ON' || state === 'TURNING_ON';
  const pending = PENDING_STATES.includes(state);
  // Turning internet back on is always possible; turning it off needs a server that can enforce the identity.
  const disabled = !can(PERMISSIONS.manageFirewall) || busy || pending || (allowed && !app.support?.supported);
  const send = async (value) => {
    setBusy(true);
    setError(null);
    try {
      onChanged(await appApi.setInternet(app.id, value));
      setConfirming(false);
    } catch (e) {
      setError(e);
      setConfirming(false);
    } finally {
      setBusy(false);
    }
  };
  const [color, icon] = STATE_STYLE[state] || STATE_STYLE.CUSTOM;
  return (
    <div>
      <div className="d-flex align-items-center gap-2">
        <div className="form-check form-switch mb-0">
          <input className="form-check-input" type="checkbox" role="switch" id={`net-${app.id}`} style={{ transform: 'scale(1.4)' }}
            checked={allowed} disabled={disabled} aria-label={t('appAccess.switchLabel', { name: app.name })}
            onChange={() => (allowed ? setConfirming(true) : send(true))} />
        </div>
        <span className={`small fw-semibold ${color}`}>
          {(pending || busy) ? <span className="spinner-border spinner-border-sm me-1" aria-hidden="true" />
            : <i className={`bi bi-${icon} me-1`} aria-hidden="true" />}
          {t(`appAccess.state.${state}`)}
        </span>
      </div>
      {state === 'FAILED' && app.internet?.message && <div className="small text-danger mt-1">{app.internet.message}</div>}
      {allowed && !app.support?.supported && app.support?.reason && <div className="small text-body-secondary mt-1">{app.support.reason}</div>}
      <ErrorAlert error={error} />
      <ConfirmDialog show={confirming} title={t('appAccess.offTitle')} confirmLabel={t('appAccess.offButton')} busy={busy}
        onConfirm={() => send(false)} onCancel={() => setConfirming(false)}>
        {t('appAccess.offText', { name: app.name, server: app.serverName || '' })}
      </ConfirmDialog>
    </div>
  );
}

/** Refresh every few seconds while any switch waits for its server. */
export function useRefreshWhilePending(apps, reload) {
  const waiting = (apps || []).some((a) => PENDING_STATES.includes(a?.internet?.state));
  useEffect(() => {
    if (!waiting) return undefined;
    const id = setInterval(reload, 4000);
    return () => clearInterval(id);
  }, [waiting, reload]);
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
  const chooseServer = (id) => {
    const s = (servers.data?.content || []).find((x) => x.id === id);
    const only = s && (KINDS[s.osFamily] || []).length === 1 ? KINDS[s.osFamily][0] : '';
    setF((x) => ({ ...x, serverId: id, identityKind: only }));
  };
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
            onChange={(e) => chooseServer(e.target.value)}>
            <option value="">{t('fw.chooseServer')}</option>
            {(servers.data?.content || []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select></div>
        <div className="col-md-6"><label className="form-label" htmlFor="ap-name">{t('appAccess.name')}</label>
          <input id="ap-name" className="form-control" required maxLength={120} value={f.name} onChange={set('name')} placeholder={t('appAccess.nameHint')} /></div>
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
  const { t } = useI18n();
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageFirewall);
  const { data, error, loading, reload } = useApi(() => appApi.list(), []);
  const [adding, setAdding] = useState(false);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);
  const [deleteError, setDeleteError] = useState(null);
  useRefreshWhilePending(data, reload);
  const testMode = (data || []).some((a) => a.support?.supported && !a.support.enforcing);
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
      {testMode && <div className="alert alert-warning small"><i className="bi bi-eye me-1" aria-hidden="true" />{t('appAccess.testModeNote')}{' '}
        <Link to="/firewall">{t('fw.title')}</Link></div>}
      <ErrorAlert error={error} onRetry={reload} />
      <ErrorAlert error={deleteError} />
      {!data && loading && <LoadingSpinner />}
      {data && data.length === 0 && <div className="card"><EmptyState icon="globe2" title={t('appAccess.none')}>{t('appAccess.noneHint')}</EmptyState></div>}
      {data && data.length > 0 && (
        <div className="card"><div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr><th>{t('appAccess.application')}</th><th>{t('fw.server')}</th><th>{t('appAccess.internet')}</th>{canManage && <th />}</tr></thead>
            <tbody>
              {data.map((a) => (
                <tr key={a.id}>
                  <td className="fw-semibold"><Link to={`/app-access/${a.id}`}>{a.name}</Link>
                    <div className="small text-body-secondary fw-normal"><IdentityText kind={a.identityKind} value={a.identityValue} /></div></td>
                  <td><Link to={`/servers/${a.serverId}`}>{a.serverName}</Link></td>
                  <td style={{ minWidth: '16rem' }}><InternetSwitch app={a} onChanged={reload} /></td>
                  {canManage && <td className="text-end"><button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDeleting(a)} aria-label={t('common.delete')}>
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
