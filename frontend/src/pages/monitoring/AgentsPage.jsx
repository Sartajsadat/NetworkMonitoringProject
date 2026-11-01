import { Link } from 'react-router-dom';
import { agentApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';

export default function AgentsPage() {
  const { t, formatNumber } = useI18n();
  const { data, error, loading, reload } = useApi(() => agentApi.list(), []);
  const counts = (data || []).reduce((m, a) => ({ ...m, [a.status]: (m[a.status] || 0) + 1 }), {});
  return (
    <>
      <PageHeader title={t('agents.title')} subtitle={t('agents.subtitle')} breadcrumbs={[{ label: t('agents.title') }]} />
      <ErrorAlert error={error} onRetry={reload} />
      {loading && !data && <LoadingSpinner />}
      {data && data.length === 0 && <div className="card"><EmptyState icon="hdd-stack" title={t('agents.noServers')} /></div>}
      {data && data.length > 0 && (
        <>
          <div className="d-flex gap-3 flex-wrap mb-3 small">
            {['ACTIVE', 'NOT_ENROLLED', 'REVOKED'].map((s) => (
              <span key={s} className="d-inline-flex align-items-center gap-1">
                <StatusBadge status={s} label={t(`agentStatus.${s}`)} /> {formatNumber(counts[s] || 0)}
              </span>
            ))}
          </div>
          <div className="card table-responsive">
            <table className="table nm-table">
              <thead><tr>
                <th>{t('agents.col.server')}</th><th>{t('agents.col.status')}</th><th>{t('agents.col.connection')}</th>
                <th>{t('agents.col.version')}</th><th>{t('agents.col.os')}</th><th>{t('agents.col.lastSeen')}</th>
                <th>{t('agents.col.errors')}</th><th />
              </tr></thead>
              <tbody>
                {data.map((a) => (
                  <tr key={a.serverId}>
                    <td>
                      <Link to={`/servers/${a.serverId}?tab=agent`}>{a.serverName}</Link>
                      <div className="small text-body-secondary nm-mono"><span className="nm-ltr">{a.serverHostname}</span></div>
                      {a.hostnameMismatch && (
                        <div className="small text-warning-emphasis"><i className="bi bi-exclamation-triangle-fill me-1" aria-hidden="true" />
                          {t('agents.mismatch')} <span className="nm-ltr">{a.reportedHostname}</span></div>
                      )}
                    </td>
                    <td><StatusBadge status={a.status} label={t(`agentStatus.${a.status}`)} />
                      {a.pendingTokenExpiresAt && <div className="small text-body-secondary mt-1">{t('agents.tokenPending')}</div>}
                    </td>
                    <td><StatusBadge status={a.connectionStatus} /></td>
                    <td className="small nm-ltr">{a.agentVersion || '—'}</td>
                    <td className="small nm-ltr">{[a.osName, a.osVersion].filter(Boolean).join(' ') || t(`os.${a.serverOsFamily}`)}</td>
                    <td className="small"><TimeAgo value={a.lastSeenAt} /></td>
                    <td className={a.lastErrorCount > 0 ? 'text-danger fw-semibold' : ''}>{a.agentId ? formatNumber(a.lastErrorCount) : '—'}</td>
                    <td className="text-end"><Link to={`/servers/${a.serverId}?tab=agent`} className="btn btn-sm btn-outline-secondary">{t('agents.manage')}</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="small text-body-secondary mt-2">{t('agents.windowsNote')}</p>
        </>
      )}
    </>
  );
}
