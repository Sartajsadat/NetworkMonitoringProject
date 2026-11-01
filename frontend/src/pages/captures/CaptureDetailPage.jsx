import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { captureApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Modal from '../../components/Modal.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import PacketsTab from './PacketsTab.jsx';
import StatisticsTab from './StatisticsTab.jsx';
import { durationText, isActive, startDownload } from './captureHelpers.js';

const TABS = ['packets', 'statistics', 'details'];

function Progress({ capture }) {
  const { t, units, formatNumber } = useI18n();
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const started = capture.startedAt ? new Date(capture.startedAt).getTime() : null;
  const elapsed = started ? Math.max(0, Math.round((now - started) / 1000)) : 0;
  const timePct = started ? Math.min(100, (elapsed / capture.durationSeconds) * 100) : 0;
  const sizePct = Math.min(100, (capture.bytesStored / capture.maxBytes) * 100);
  return (
    <div className="row g-3 small">
      <div className="col-md-6">
        <div className="d-flex justify-content-between">
          <span>{t('capture.elapsed')}</span>
          <span className="nm-num">{t('capture.elapsedOf', { s: formatNumber(elapsed), total: formatNumber(capture.durationSeconds) })}</span>
        </div>
        <div className="progress" role="progressbar" aria-label={t('capture.elapsed')} aria-valuenow={Math.round(timePct)} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar progress-bar-striped progress-bar-animated" style={{ width: `${timePct}%` }} />
        </div>
      </div>
      <div className="col-md-6">
        <div className="d-flex justify-content-between">
          <span>{t('capture.received')}</span>
          <span className="nm-num">{units.bytes(capture.bytesStored)} / {units.bytes(capture.maxBytes)}</span>
        </div>
        <div className="progress" role="progressbar" aria-label={t('capture.received')} aria-valuenow={Math.round(sizePct)} aria-valuemin={0} aria-valuemax={100}>
          <div className="progress-bar bg-info" style={{ width: `${sizePct}%` }} />
        </div>
      </div>
    </div>
  );
}

function Field({ label, children }) {
  return (
    <div className="col-sm-6 col-lg-4 col-xl-3">
      <div className="small text-body-secondary">{label}</div>
      <div className="small">{children}</div>
    </div>
  );
}

export default function CaptureDetailPage() {
  const { id } = useParams();
  const i18n = useI18n();
  const { t, units, formatNumber, formatDateTime } = i18n;
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const tab = TABS.includes(params.get('tab')) ? params.get('tab') : 'packets';
  const { data: c, error, loading, reload, setData } = useApi(() => captureApi.get(id), [id]);
  const [confirmStop, setConfirmStop] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteReason, setDeleteReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState(null);
  const active = isActive(c);
  const canManage = can(PERMISSIONS.manageCaptures);

  useEffect(() => {
    if (!active) return undefined;
    const timer = setInterval(reload, 3000);
    return () => clearInterval(timer);
  }, [active, reload]);

  const run = async (fn) => {
    setBusy(true);
    setActionError(null);
    try {
      await fn();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(false);
    }
  };
  const stop = () => run(async () => { setData(await captureApi.stop(id)); setConfirmStop(false); });
  const download = () => run(async () => startDownload((await captureApi.downloadTicket(id)).url));
  const deleteFiles = () => run(async () => {
    setData(await captureApi.deleteFiles(id, deleteReason.trim()));
    setDeleteOpen(false);
  });

  if (!c && loading) return <LoadingSpinner />;
  if (!c) return <ErrorAlert error={error} onRetry={reload} />;

  const actions = (
    <>
      {canManage && active && (
        <button type="button" className="btn btn-sm btn-danger" onClick={() => setConfirmStop(true)} disabled={busy || c.status === 'STOPPING'}>
          <i className="bi bi-stop-circle me-1" aria-hidden="true" />{t('capture.stop')}
        </button>
      )}
      {canManage && c.filesAvailable && c.segmentsCount > 0 && (
        <button type="button" className="btn btn-sm btn-outline-primary" onClick={download} disabled={busy}>
          <i className="bi bi-download me-1" aria-hidden="true" />{t('capture.download')}
        </button>
      )}
      {can(PERMISSIONS.deleteCaptureFiles) && !active && c.filesAvailable && (
        <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDeleteOpen(true)} disabled={busy}>
          <i className="bi bi-trash me-1" aria-hidden="true" />{t('capture.deleteFiles')}
        </button>
      )}
    </>
  );

  return (
    <>
      <PageHeader
        title={<span className="d-inline-flex align-items-center gap-2 flex-wrap">{c.name} <StatusBadge status={c.status} label={t(`capture.status.${c.status}`)} /></span>}
        subtitle={<>{c.serverName} · <span className="nm-mono nm-ltr">{c.interfaceName}</span>{c.reference && <> · <span className="nm-ltr">{c.reference}</span></>}</>}
        breadcrumbs={[{ label: t('captures.title'), to: '/captures' }, { label: c.name }]}
        actions={actions}
      />
      <ErrorAlert error={actionError} />
      {error && <ErrorAlert error={error} onRetry={reload} />}

      <div className="card mb-3"><div className="card-body">
        {c.statusMessage && (
          <div className={`alert ${c.status === 'FAILED' || c.status === 'UNSUPPORTED' ? 'alert-danger' : 'alert-secondary'} small py-2`}>
            <span className="nm-ltr">{c.statusMessage}</span>
          </div>
        )}
        {active && <div className="mb-3"><Progress capture={c} /></div>}
        <div className="row g-3">
          <Field label={t('capture.filter')}>
            {c.captureFilter ? <code className="nm-ltr" dir="ltr">{c.captureFilter}</code> : <span className="text-body-secondary">{t('capture.noFilter')}</span>}
          </Field>
          <Field label={t('capture.packets')}>
            <span className="nm-num">{formatNumber(c.packetsIndexed)}</span>
            {c.agentPackets != null && <span className="text-body-secondary"> {t('capture.agentCounted', { n: formatNumber(c.agentPackets) })}</span>}
          </Field>
          <Field label={t('capture.stored')}>
            <span className="nm-num">{units.bytes(c.bytesStored)}</span> <span className="text-body-secondary">({t('capture.pieces', { n: formatNumber(c.segmentsCount) })})</span>
          </Field>
          <Field label={t('capture.limits')}>{durationText(c.durationSeconds, i18n)} · {units.bytes(c.maxBytes)}</Field>
          <Field label={t('capture.requested')}>{c.requestedBy} · <TimeAgo value={c.requestedAt} /></Field>
          <Field label={t('capture.window')}>
            {c.startedAt ? formatDateTime(c.startedAt) : '—'}{c.endedAt && <> → {formatDateTime(c.endedAt)}</>}
          </Field>
          <Field label={t('capture.files')}>
            {c.filesAvailable ? t('capture.keptUntil', { d: formatDateTime(c.expiresAt) })
              : <span className="text-body-secondary">{t('capture.deletedOn', { d: formatDateTime(c.filesDeletedAt) })}</span>}
          </Field>
          {c.stopRequestedBy && <Field label={t('capture.stoppedBy')}>{c.stopRequestedBy}</Field>}
        </div>
      </div></div>

      <ul className="nav nav-tabs nm-tabs mb-3" role="tablist">
        {TABS.map((k) => (
          <li className="nav-item" key={k} role="presentation">
            <button type="button" role="tab" aria-selected={tab === k} className={`nav-link ${tab === k ? 'active' : ''}`}
              onClick={() => setParams(k === 'packets' ? {} : { tab: k }, { replace: true })}>
              {t(`analyzer.tab.${k}`)}
            </button>
          </li>
        ))}
      </ul>

      {!c.filesAvailable && tab !== 'details' ? (
        <div className="alert alert-secondary">{t('capture.filesGone')}</div>
      ) : (
        <>
          {tab === 'packets' && <PacketsTab capture={c} live={active} canSeeBytes={canManage} />}
          {tab === 'statistics' && <StatisticsTab capture={c} />}
        </>
      )}
      {tab === 'details' && (
        <div className="card"><div className="card-body">
          <dl className="row mb-0 small">
            <dt className="col-sm-3">{t('capture.reason')}</dt><dd className="col-sm-9">{c.reason}</dd>
            {c.description && <><dt className="col-sm-3">{t('capture.description')}</dt><dd className="col-sm-9">{c.description}</dd></>}
            <dt className="col-sm-3">{t('captures.col.server')}</dt>
            <dd className="col-sm-9"><Link to={`/servers/${c.serverId}`}>{c.serverName}</Link> <span className="nm-ltr text-body-secondary">({c.serverHostname})</span></dd>
            <dt className="col-sm-3">{t('captureNew.snaplen')}</dt><dd className="col-sm-9 nm-num">{t('capture.snaplenBytes', { n: formatNumber(c.snaplen) })}</dd>
            <dt className="col-sm-3">{t('captureNew.retention')}</dt><dd className="col-sm-9">{t('capture.retentionDays', { n: formatNumber(c.retentionDays) })}</dd>
            <dt className="col-sm-3">{t('capture.id')}</dt><dd className="col-sm-9 nm-mono nm-ltr">{c.id}</dd>
          </dl>
          <p className="small text-body-secondary mt-3 mb-0">{t('capture.auditNote')}</p>
        </div></div>
      )}

      <ConfirmDialog show={confirmStop} title={t('capture.stopTitle')} confirmLabel={t('capture.stop')} busy={busy}
        onConfirm={stop} onCancel={() => setConfirmStop(false)}>
        {t('capture.stopText')}
      </ConfirmDialog>
      <Modal show={deleteOpen} title={t('capture.deleteTitle')} onClose={() => !busy && setDeleteOpen(false)}
        footer={(
          <>
            <button type="button" className="btn btn-outline-secondary" onClick={() => setDeleteOpen(false)} disabled={busy}>{t('common.cancel')}</button>
            <button type="button" className="btn btn-danger" onClick={deleteFiles} disabled={busy || deleteReason.trim().length < 5}>{t('capture.deleteFiles')}</button>
          </>
        )}>
        <p className="small">{t('capture.deleteText')}</p>
        <label className="form-label" htmlFor="del-reason">{t('capture.deleteReason')}</label>
        <textarea id="del-reason" className="form-control" rows={2} minLength={5} maxLength={500} value={deleteReason}
          onChange={(e) => setDeleteReason(e.target.value)} />
      </Modal>
    </>
  );
}
