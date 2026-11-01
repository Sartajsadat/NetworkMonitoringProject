import { useEffect, useState } from 'react';
import { agentApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import CopyButton from '../../components/CopyButton.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Modal from '../../components/Modal.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';

function Row({ label, children }) {
  return (
    <div className="row py-1 border-bottom small">
      <div className="col-sm-5 text-body-secondary">{label}</div>
      <div className="col-sm-7">{children ?? '—'}</div>
    </div>
  );
}

const UNIT_RE = /^[A-Za-z0-9@._:$-]{1,128}$/;

export default function AgentTab({ serverId, latest, canManage, onChanged }) {
  const { t, formatNumber } = useI18n();
  const { data: a, error, loading, reload } = useApi(() => agentApi.forServer(serverId), [serverId]);
  const [token, setToken] = useState(null);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState(null);
  const [confirmRevoke, setConfirmRevoke] = useState(false);
  const [interval, setIntervalValue] = useState(30);
  const [servicesText, setServicesText] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (a?.config) {
      setIntervalValue(a.config.reportIntervalSeconds);
      setServicesText(a.config.services.join('\n'));
    }
  }, [a]);

  const createToken = async () => {
    setBusy(true);
    setActionError(null);
    try {
      setToken(await agentApi.createToken(serverId));
      reload();
    } catch (e) {
      setActionError(e);
    } finally {
      setBusy(false);
    }
  };
  const revoke = async () => {
    setBusy(true);
    setActionError(null);
    try {
      await agentApi.revoke(serverId);
      setConfirmRevoke(false);
      reload();
      onChanged?.();
    } catch (e) {
      setActionError(e);
      setConfirmRevoke(false);
    } finally {
      setBusy(false);
    }
  };
  const services = servicesText.split(/\s+/).map((s) => s.trim()).filter(Boolean);
  const badServices = services.filter((s) => !UNIT_RE.test(s));
  const saveConfig = async (e) => {
    e.preventDefault();
    setBusy(true);
    setActionError(null);
    setSaved(false);
    try {
      await agentApi.updateConfig(serverId, { reportIntervalSeconds: Number(interval), services });
      setSaved(true);
      reload();
    } catch (err) {
      setActionError(err);
    } finally {
      setBusy(false);
    }
  };

  if (loading && !a) return <LoadingSpinner />;
  if (error) return <ErrorAlert error={error} onRetry={reload} />;
  if (!a) return null;

  const platformUrl = token?.platformUrl || window.location.origin;
  const windows = a.serverOsFamily === 'WINDOWS';
  const os = windows ? 'windows' : 'linux';
  // Displayed on several lines for reading; the copy button copies one line.
  const installCmd = !token ? '' : windows
    ? `.\\deploy\\windows\\install-agent.ps1 \`\n  -Jar .\\netmon-agent.jar -WinSW .\\WinSW-x64.exe \`\n  -Url ${platformUrl} \`\n  -Token ${token.token}`
    : `sudo ./deploy/linux/install-agent.sh \\\n  --jar netmon-agent.jar \\\n  --url ${platformUrl} \\\n  --token ${token.token}`;
  const oneLine = installCmd.replace(/ (\\|`)\n\s+/g, ' ');
  const collectors = latest?.report?.collectors || [];

  return (
    <div className="row g-3">
      <div className="col-lg-6">
        <div className="card h-100">
          <div className="card-header d-flex justify-content-between align-items-center">
            <h2 className="h6 mb-0">{t('agent.title')}</h2>
            <StatusBadge status={a.status} label={t(`agentStatus.${a.status}`)} />
          </div>
          <div className="card-body">
            <ErrorAlert error={actionError} />
            {a.status === 'NOT_ENROLLED' && <p className="small text-body-secondary">{t('agent.notEnrolledText')}</p>}
            {a.status === 'REVOKED' && <p className="small text-body-secondary">{t('agent.revokedText')}</p>}
            {a.platformMismatch && (
              <div className="alert alert-warning small py-2">
                <i className="bi bi-exclamation-triangle-fill me-1" aria-hidden="true" />
                {t('agent.platformMismatch', { agent: t(`os.${a.platform}`), server: t(`os.${a.serverOsFamily}`) })}
              </div>
            )}
            {a.hostnameMismatch && (
              <div className="alert alert-warning small py-2">
                <i className="bi bi-exclamation-triangle-fill me-1" aria-hidden="true" />
                {t('agent.hostnameMismatch', { reported: a.reportedHostname, registered: a.serverHostname })}
              </div>
            )}
            {a.agentId && (
              <>
                <Row label={t('agent.connection')}><StatusBadge status={a.connectionStatus} /></Row>
                <Row label={t('agent.version')}><span className="nm-ltr">{a.agentVersion}</span></Row>
                <Row label={t('agent.os')}><span className="nm-ltr">{[a.osName, a.osVersion, a.architecture].filter(Boolean).join(' · ')}</span></Row>
                <Row label={t('agent.kernel')}><span className="nm-ltr">{a.kernel}</span></Row>
                <Row label={t('agent.reportedHostname')}><span className="nm-ltr">{a.reportedHostname}</span></Row>
                <Row label={t('agent.lastSeen')}><TimeAgo value={a.lastSeenAt} />{a.lastReportIp && <> · <span className="nm-ltr nm-mono">{a.lastReportIp}</span></>}</Row>
                <Row label={t('agent.collectorErrors')}>{a.lastErrorCount > 0
                  ? <span className="text-danger fw-semibold">{formatNumber(a.lastErrorCount)}</span> : formatNumber(0)}</Row>
                <Row label={t('agent.enrolled')}><TimeAgo value={a.enrolledAt} />{a.enrolledFromIp && <> · <span className="nm-ltr nm-mono">{a.enrolledFromIp}</span></>}</Row>
                <Row label={t('agent.credentialRotated')}><TimeAgo value={a.credentialRotatedAt} never={t('agent.notYet')} /></Row>
                <Row label={t('agent.agentId')}><code className="nm-ltr small">{a.agentId}</code></Row>
                {a.status === 'REVOKED' && <Row label={t('agent.revoked')}><TimeAgo value={a.revokedAt} /> {a.revokedBy && <>({t('serverDetail.by')} <span className="nm-ltr">{a.revokedBy}</span>)</>}</Row>}
              </>
            )}
            {a.pendingTokenExpiresAt && (
              <p className="small text-body-secondary mt-2 mb-0">
                <i className="bi bi-hourglass-split me-1" aria-hidden="true" />
                {t('agent.pendingToken')} <TimeAgo value={a.pendingTokenExpiresAt} />
              </p>
            )}
          </div>
          {canManage && (
            <div className="card-footer d-flex gap-2 flex-wrap">
              <button type="button" className="btn btn-sm btn-primary" onClick={createToken} disabled={busy}>
                <i className="bi bi-key me-1" aria-hidden="true" />
                {a.status === 'ACTIVE' ? t('agent.reEnroll') : t('agent.enroll')}
              </button>
              {a.status === 'ACTIVE' && (
                <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setConfirmRevoke(true)} disabled={busy}>
                  <i className="bi bi-slash-circle me-1" aria-hidden="true" />{t('agent.revoke')}
                </button>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="col-lg-6">
        <form className="card mb-3" onSubmit={saveConfig}>
          <div className="card-header"><h2 className="h6 mb-0">{t('agent.settings')}</h2></div>
          <div className="card-body">
            <div className="mb-3">
              <label htmlFor="agent-interval" className="form-label">{t('agent.interval')}</label>
              <input id="agent-interval" type="number" min="10" max="3600" className="form-control" style={{ maxWidth: 160 }}
                value={interval} onChange={(e) => setIntervalValue(e.target.value)} disabled={!canManage} dir="ltr" />
              <div className="form-text">{t('agent.intervalHint')}</div>
            </div>
            <div className="mb-2">
              <label htmlFor="agent-services" className="form-label">{t('agent.services')}</label>
              <textarea id="agent-services" className={`form-control nm-mono nm-ltr ${badServices.length ? 'is-invalid' : ''}`} dir="ltr" rows={5}
                value={servicesText} onChange={(e) => setServicesText(e.target.value)} disabled={!canManage}
                placeholder={'nginx.service\npostgresql.service'} />
              {badServices.length > 0 && <div className="invalid-feedback">{t('agent.badServices', { list: badServices.join(', ') })}</div>}
              <div className="form-text">{t('agent.servicesHint')}</div>
            </div>
            {saved && <div className="alert alert-success small py-2 mb-0" role="status">{t('agent.saved')}</div>}
          </div>
          {canManage && (
            <div className="card-footer">
              <button type="submit" className="btn btn-sm btn-primary" disabled={busy || badServices.length > 0}>{t('common.save')}</button>
            </div>
          )}
        </form>

        <div className="card">
          <div className="card-header"><h2 className="h6 mb-0">{t('agent.collectors')}</h2></div>
          {collectors.length === 0 ? <div className="card-body small text-body-secondary">{t('chart.noData')}</div> : (
            <ul className="list-group list-group-flush">
              {collectors.map((c) => (
                <li key={c.name} className="list-group-item d-flex justify-content-between align-items-start gap-2 small">
                  <div><span className="nm-mono nm-ltr">{c.name}</span>{c.message && <div className="text-body-secondary nm-ltr">{c.message}</div>}</div>
                  <StatusBadge status={c.status} label={t(`collector.${c.status}`)} />
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <Modal show={Boolean(token)} title={t('agent.tokenTitle')} onClose={() => setToken(null)} size="lg">
        {token && (
          <>
            <div className="alert alert-warning small py-2">
              <i className="bi bi-shield-exclamation me-1" aria-hidden="true" />
              {t('agent.tokenOnce')} <TimeAgo value={token.expiresAt} />
            </div>
            <label className="form-label small mb-1">{t('agent.token')}</label>
            <div className="d-flex gap-2 align-items-start mb-3">
              <div id="enroll-token" className="nm-code flex-grow-1">{token.token}</div>
              <CopyButton text={token.token} targetId="enroll-token" />
            </div>
            <h3 className="h6">{t(`agent.${os}.installTitle`)}</h3>
            <ol className="small">
              {(windows ? [1, 2, 3, 4] : [1, 2, 3]).map((n) => <li key={n}>{t(`agent.${os}.step${n}`)}</li>)}
            </ol>
            <div className="d-flex gap-2 align-items-start">
              <div id="install-cmd" className="nm-code flex-grow-1">{installCmd}</div>
              <CopyButton text={oneLine} targetId="install-cmd" />
            </div>
            <p className="small text-body-secondary mt-2 mb-0">{t(`agent.${os}.installNote`)}</p>
          </>
        )}
      </Modal>

      <ConfirmDialog show={confirmRevoke} title={t('agent.revokeTitle')} confirmLabel={t('agent.revoke')} busy={busy}
        onConfirm={revoke} onCancel={() => setConfirmRevoke(false)}>
        {t('agent.revokeBody')}
      </ConfirmDialog>
    </div>
  );
}
