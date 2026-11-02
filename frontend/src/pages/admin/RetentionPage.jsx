import { useState } from 'react';
import { opsApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Modal from '../../components/Modal.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';

function EditPolicy({ policy, onClose, onSaved }) {
  const { t } = useI18n();
  const [days, setDays] = useState(String(policy.retentionDays));
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const shorter = Number(days) < policy.retentionDays;
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try { onSaved(await opsApi.updateRetention(policy.dataType, Number(days), reason)); } catch (err) { setError(err); setBusy(false); }
  };
  return (
    <Modal show title={t('retention.editTitle', { type: t(`retention.type.${policy.dataType}`) })} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
        <button type="submit" form="ret-form" className="btn btn-primary" disabled={busy}>{t('common.save')}</button></>)}>
      <form id="ret-form" onSubmit={save}>
        <label className="form-label" htmlFor="ret-days">{t('retention.days')}</label>
        <input id="ret-days" type="number" className="form-control" dir="ltr" required min={policy.minDays} max={policy.maxDays} value={days}
          onChange={(e) => setDays(e.target.value)} />
        <div className="form-text">{t('retention.range', { min: policy.minDays, max: policy.maxDays })}</div>
        {shorter && <div className="alert alert-warning small mt-3 mb-0">{t('retention.shorterWarning')}</div>}
        <label className="form-label mt-3" htmlFor="ret-reason">{t('fw.reason')}</label>
        <textarea id="ret-reason" className="form-control" required minLength={5} maxLength={500} rows={2} value={reason}
          onChange={(e) => setReason(e.target.value)} />
        <div className="mt-3"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}

export default function RetentionPage() {
  const { t, units, formatNumber, formatDateTime } = useI18n();
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageRetention);
  const { data, error, loading, reload } = useApi(() => opsApi.retention(), []);
  const [editing, setEditing] = useState(null);
  const [confirmRun, setConfirmRun] = useState(false);
  const [running, setRunning] = useState(false);
  const [runResult, setRunResult] = useState(null);
  const [runError, setRunError] = useState(null);
  const run = async () => {
    setRunning(true);
    setRunError(null);
    try { setRunResult(await opsApi.runRetention()); setConfirmRun(false); reload(); } catch (e) { setRunError(e); setConfirmRun(false); } finally { setRunning(false); }
  };
  return (
    <>
      <PageHeader title={t('nav.retention')} subtitle={t('retention.subtitle')} breadcrumbs={[{ label: t('nav.retention') }]}
        actions={canManage && <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setConfirmRun(true)}>
          <i className="bi bi-play-circle me-1" aria-hidden="true" />{t('retention.runNow')}</button>} />
      <div className="alert alert-light border small"><i className="bi bi-info-circle me-1" aria-hidden="true" />{t('retention.explain')}</div>
      <ErrorAlert error={error} onRetry={reload} />
      <ErrorAlert error={runError} />
      {runResult && <div className="alert alert-success small" role="status">{t('retention.ran', { n: formatNumber(runResult.reduce((a, r) => a + r.deleted, 0)) })}</div>}
      {!data && loading && <LoadingSpinner />}
      {data && (
        <div className="card"><div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr><th>{t('retention.data')}</th><th>{t('retention.keep')}</th><th>{t('retention.stored')}</th><th>{t('retention.oldest')}</th>
              <th>{t('retention.lastRun')}</th>{canManage && <th />}</tr></thead>
            <tbody>
              {data.map((p) => (
                <tr key={p.dataType}>
                  <td className="fw-semibold">{t(`retention.type.${p.dataType}`)}<div className="small text-body-secondary fw-normal">{t(`retention.help.${p.dataType}`)}</div></td>
                  <td>{t('retention.daysValue', { n: formatNumber(p.retentionDays) })}
                    <div className="small text-body-secondary">{p.fromEnvironment ? t('retention.fromEnv') : t('retention.changedBy', { who: p.updatedBy })}</div></td>
                  <td className="small">{p.bytes != null ? units.bytes(p.bytes) : '—'}
                    {p.rows != null && p.dataType !== 'CAPTURES' && <div className="text-body-secondary">{t('retention.rows', { n: formatNumber(p.rows) })}</div>}</td>
                  <td className="small">{p.oldest ? formatDateTime(p.oldest) : '—'}</td>
                  <td className="small">{p.lastRunAt ? <TimeAgo value={p.lastRunAt} /> : t('retention.never')}
                    {p.lastError ? <div className="text-danger">{p.lastError}</div>
                      : p.lastRunAt && <div className="text-body-secondary">{t('retention.deleted', { n: formatNumber(p.lastDeleted ?? 0) })}</div>}</td>
                  {canManage && <td><button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEditing(p)}
                    aria-label={t('common.edit')}><i className="bi bi-pencil" aria-hidden="true" /></button></td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div></div>
      )}
      {editing && <EditPolicy policy={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload(); }} />}
      <ConfirmDialog show={confirmRun} title={t('retention.runNow')} confirmLabel={t('retention.runNow')} busy={running}
        onConfirm={run} onCancel={() => setConfirmRun(false)}>{t('retention.runText')}</ConfirmDialog>
    </>
  );
}
