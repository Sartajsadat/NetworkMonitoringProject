import { useState } from 'react';
import { Link } from 'react-router-dom';
import { firewallApi, serverApi } from '../../api/endpoints.js';
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
import TimeAgo from '../../components/TimeAgo.jsx';
import { ModeBadge } from './firewallText.jsx';

function AddTarget({ existing, onClose, onSaved }) {
  const { t } = useI18n();
  const [kind, setKind] = useState('server');
  const [serverId, setServerId] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const servers = useApi(() => serverApi.search({ size: 200, sort: 'name,asc' }), []);
  const taken = new Set(existing.map((i) => i.serverId).filter(Boolean));
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSaved(await firewallApi.createIntegration(kind === 'server' ? { serverId, description: description || null }
        : { name, description: description || null }));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  return (
    <Modal show size="lg" title={t('fw.add')} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
        <button type="submit" form="fw-add" className="btn btn-primary" disabled={busy}>{t('common.save')}</button></>)}>
      <form id="fw-add" onSubmit={save} className="row g-3">
        <fieldset className="col-12">
          <legend className="form-label">{t('fw.kind')}</legend>
          <div className="form-check">
            <input className="form-check-input" type="radio" id="k-server" checked={kind === 'server'} onChange={() => setKind('server')} />
            <label className="form-check-label" htmlFor="k-server"><span className="fw-semibold">{t('fw.kind.SERVER_AGENT')}</span>
              <div className="small text-body-secondary">{t('fw.kindHelp.SERVER_AGENT')}</div></label>
          </div>
          <div className="form-check mt-2">
            <input className="form-check-input" type="radio" id="k-central" checked={kind === 'central'} onChange={() => setKind('central')} />
            <label className="form-check-label" htmlFor="k-central"><span className="fw-semibold">{t('fw.kind.CENTRAL_DRY_RUN')}</span>
              <div className="small text-body-secondary">{t('fw.kindHelp.CENTRAL_DRY_RUN')}</div></label>
          </div>
        </fieldset>
        {kind === 'server' ? (
          <div className="col-12"><label className="form-label" htmlFor="fw-server">{t('fw.server')}</label>
            <select id="fw-server" className="form-select" required value={serverId} onChange={(e) => setServerId(e.target.value)}>
              <option value="">{t('fw.chooseServer')}</option>
              {(servers.data?.content || []).map((s) => (
                <option key={s.id} value={s.id} disabled={taken.has(s.id)}>{s.name}{taken.has(s.id) ? ` (${t('fw.alreadyAdded')})` : ''}</option>
              ))}
            </select></div>
        ) : (
          <div className="col-12"><label className="form-label" htmlFor="fw-name">{t('fw.centralName')}</label>
            <input id="fw-name" className="form-control" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)}
              placeholder={t('fw.centralNameHint')} /></div>
        )}
        <div className="col-12"><label className="form-label" htmlFor="fw-desc">{t('subnets.description')}</label>
          <input id="fw-desc" className="form-control" maxLength={500} value={description} onChange={(e) => setDescription(e.target.value)} /></div>
        <div className="col-12"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}

function ModeDialog({ target, onClose, onSaved }) {
  const { t } = useI18n();
  const { can } = useAuth();
  const toEnforce = target.mode !== 'ENFORCE';
  const [reason, setReason] = useState('');
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const blocked = toEnforce && (!can(PERMISSIONS.enforceFirewall) || !target.target?.enforcementAllowedOnServer);
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSaved(await firewallApi.setMode(target.id, toEnforce ? 'ENFORCE' : 'DRY_RUN', reason));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  return (
    <Modal show title={toEnforce ? t('fw.enforceTitle') : t('fw.dryRunTitle')} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
        <button type="submit" form="fw-mode" className={`btn ${toEnforce ? 'btn-danger' : 'btn-primary'}`}
          disabled={busy || blocked || (toEnforce && !confirm)}>{toEnforce ? t('fw.enforceButton') : t('fw.dryRunButton')}</button></>)}>
      <form id="fw-mode" onSubmit={save}>
        <p className="fw-semibold">{target.name}</p>
        {toEnforce ? (
          <>
            <div className="alert alert-warning small">{t('fw.enforceWarning')}</div>
            {!can(PERMISSIONS.enforceFirewall) && <div className="alert alert-danger small">{t('fw.enforceSuperAdmin')}</div>}
            {can(PERMISSIONS.enforceFirewall) && !target.target?.enforcementAllowedOnServer && (
              <div className="alert alert-danger small">{t('fw.enforceNotAllowed')}</div>)}
          </>
        ) : <p className="small">{t('fw.dryRunText')}</p>}
        <label className="form-label" htmlFor="fw-reason">{t('fw.reason')}</label>
        <textarea id="fw-reason" className="form-control" required minLength={5} maxLength={500} rows={2} value={reason}
          onChange={(e) => setReason(e.target.value)} placeholder={t('fw.modeReasonHint')} />
        {toEnforce && !blocked && (
          <div className="form-check mt-3">
            <input id="fw-confirm" type="checkbox" className="form-check-input" checked={confirm} onChange={(e) => setConfirm(e.target.checked)} />
            <label className="form-check-label" htmlFor="fw-confirm">{t('fw.enforceConfirm')}</label>
          </div>
        )}
        <div className="mt-3"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}

function TargetState({ i }) {
  const { t } = useI18n();
  if (i.kind !== 'SERVER_AGENT') return <span className="small text-body-secondary">{t('fw.noAdapter')}</span>;
  const s = i.target;
  if (!s) return '—';
  return (
    <div className="small">
      <div>
        {s.agentFirewallEnabled
          ? <><i className="bi bi-shield-check text-success me-1" aria-hidden="true" />{s.backend ? t(`fw.backend.${s.backend}`) : t('fw.backend.NONE')}</>
          : <><i className="bi bi-toggle-off text-body-secondary me-1" aria-hidden="true" />{t('fw.agentNotEnabled')}</>}
      </div>
      <div className="text-body-secondary">
        {s.enforcementAllowedOnServer ? t('fw.serverAllowsEnforce') : t('fw.serverDryRunOnly')}
        {s.reportedAt && <> · <TimeAgo value={s.reportedAt} /></>}
      </div>
      {s.message && <div className="text-body-secondary nm-ltr" dir="ltr">{s.message}</div>}
    </div>
  );
}

export default function FirewallIntegrationsPage() {
  const { t, formatNumber } = useI18n();
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageFirewall);
  const { data, error, loading, reload } = useApi(() => firewallApi.integrations(), []);
  const [adding, setAdding] = useState(false);
  const [mode, setMode] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);
  const [deleteError, setDeleteError] = useState(null);
  const remove = async () => {
    setBusy(true);
    setDeleteError(null);
    try { await firewallApi.deleteIntegration(deleting.id); setDeleting(null); reload(); } catch (e) { setDeleteError(e); } finally { setBusy(false); }
  };
  return (
    <>
      <PageHeader title={t('fw.title')} subtitle={t('fw.subtitle')} breadcrumbs={[{ label: t('fw.title') }]}
        actions={canManage && <button type="button" className="btn btn-sm btn-primary" onClick={() => setAdding(true)}>
          <i className="bi bi-plus-lg me-1" aria-hidden="true" />{t('fw.add')}</button>} />
      <div className="alert alert-light border small">
        <i className="bi bi-info-circle me-1" aria-hidden="true" />{t('fw.explain')}
      </div>
      <ErrorAlert error={error} onRetry={reload} />
      <ErrorAlert error={deleteError} />
      {!data && loading && <LoadingSpinner />}
      {data && data.length === 0 && <div className="card"><EmptyState icon="bricks" title={t('fw.none')}>{t('fw.noneHint')}</EmptyState></div>}
      {data && data.length > 0 && (
        <div className="card"><div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr>
              <th>{t('fw.target')}</th><th>{t('fw.scope')}</th><th>{t('fw.detected')}</th><th>{t('fw.mode')}</th>
              <th>{t('fw.activeRules')}</th>{canManage && <th />}
            </tr></thead>
            <tbody>
              {data.map((i) => (
                <tr key={i.id}>
                  <td className="fw-semibold">
                    {i.kind === 'SERVER_AGENT' ? <Link to={`/servers/${i.serverId}`}>{i.serverName}</Link> : i.name}
                    {i.description && <div className="small text-body-secondary fw-normal">{i.description}</div>}
                  </td>
                  <td className="small">{t(`fw.kind.${i.kind}`)}
                    <div className="text-body-secondary">{i.kind === 'SERVER_AGENT' ? t('fw.scopeShort.server') : t('fw.scopeShort.central')}</div></td>
                  <td><TargetState i={i} /></td>
                  <td><ModeBadge mode={i.mode} enforcing={i.enforcing} />
                    {i.modeChangedBy && <div className="small text-body-secondary">{i.modeChangedBy} · <TimeAgo value={i.modeChangedAt} /></div>}</td>
                  <td><Link to={`/ip-policies?integrationId=${i.id}`}>{formatNumber(i.activeRules)}</Link></td>
                  {canManage && (
                    <td className="text-nowrap">
                      {i.kind === 'SERVER_AGENT' && (
                        <button type="button" className="btn btn-sm btn-outline-secondary me-1" onClick={() => setMode(i)}>
                          {i.mode === 'ENFORCE' ? t('fw.dryRunButton') : t('fw.enforceButton')}</button>)}
                      <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDeleting(i)} aria-label={t('common.delete')}>
                        <i className="bi bi-trash" aria-hidden="true" /></button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div></div>
      )}
      <div className="small text-body-secondary mt-3"><i className="bi bi-life-preserver me-1" aria-hidden="true" />{t('fw.recoveryHint')}</div>
      {adding && <AddTarget existing={data || []} onClose={() => setAdding(false)} onSaved={() => { setAdding(false); reload(); }} />}
      {mode && <ModeDialog target={mode} onClose={() => setMode(null)} onSaved={() => { setMode(null); reload(); }} />}
      <ConfirmDialog show={Boolean(deleting)} title={t('fw.deleteTitle')} confirmLabel={t('common.delete')} busy={busy}
        onConfirm={remove} onCancel={() => setDeleting(null)}>{t('fw.deleteText', { name: deleting?.name || '' })}</ConfirmDialog>
    </>
  );
}
