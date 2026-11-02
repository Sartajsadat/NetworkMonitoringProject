import { opsApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';

const STYLE = { OK: ['success', 'check-circle-fill'], WARNING: ['warning', 'exclamation-triangle-fill'], ERROR: ['danger', 'x-octagon-fill'] };

function Level({ status }) {
  const { t } = useI18n();
  const [bg, icon] = STYLE[status] || ['light', 'question-circle'];
  return (
    <span className={`badge text-bg-${bg} ${bg === 'warning' ? 'text-dark' : ''} d-inline-flex align-items-center gap-1`}>
      <i className={`bi bi-${icon}`} aria-hidden="true" />{t(`health.level.${status}`)}
    </span>
  );
}

function Pairs({ rows }) {
  return (
    <dl className="row small mb-0">
      {rows.filter(([, v]) => v !== undefined).map(([k, v]) => (
        <div className="contents" key={k} style={{ display: 'contents' }}>
          <dt className="col-6 fw-normal text-body-secondary">{k}</dt>
          <dd className="col-6 mb-1 nm-ltr" dir="ltr">{v === null || v === '' ? '—' : v}</dd>
        </div>
      ))}
    </dl>
  );
}

export default function HealthPage() {
  const { t, units, formatNumber, formatDuration } = useI18n();
  const { data: h, error, loading, reload } = useApi(() => opsApi.health(), []);
  return (
    <>
      <PageHeader title={t('nav.config')} subtitle={t('health.subtitle')} breadcrumbs={[{ label: t('nav.config') }]}
        actions={<button type="button" className="btn btn-sm btn-outline-secondary" onClick={reload}><i className="bi bi-arrow-clockwise me-1" aria-hidden="true" />{t('common.refresh')}</button>} />
      <ErrorAlert error={error} onRetry={reload} />
      {!h && loading && <LoadingSpinner />}
      {h && (
        <>
          <div className="card mb-3"><div className="card-body">
            <div className="d-flex align-items-center gap-2 mb-2"><h2 className="h6 mb-0">{t('health.overall')}</h2><Level status={h.overall} />
              <span className="small text-body-secondary ms-auto"><TimeAgo value={h.time} /></span></div>
            <ul className="list-unstyled mb-0">
              {h.checks.map((c, n) => (
                <li key={n} className="d-flex gap-2 align-items-start py-1 border-top">
                  <Level status={c.status} /><span className="small"><span className="fw-semibold">{t(`health.check.${c.key}`)}</span>
                    {' — '}<span>{c.message}</span></span>
                </li>
              ))}
            </ul>
          </div></div>
          <div className="row g-3">
            <div className="col-lg-6"><div className="card h-100"><div className="card-body"><h2 className="h6">{t('health.database')}</h2>
              <Pairs rows={[[t('health.version'), h.database.version], [t('health.schema'), h.database.schemaVersion],
                [t('health.size'), h.database.sizeBytes != null ? units.bytes(h.database.sizeBytes) : null],
                [t('health.connections'), `${h.database.connections} / ${h.database.maxConnections}`],
                [t('health.clockSkew'), h.database.clockSkewMillis != null ? `${h.database.clockSkewMillis} ms` : null]]} />
              <h3 className="h6 mt-3">{t('health.largestTables')}</h3>
              <table className="table table-sm small mb-0"><tbody>
                {h.largestTables.map((x) => <tr key={x.table}><td className="nm-mono" dir="ltr">{x.table}</td><td className="text-end">{units.bytes(x.bytes)}</td>
                  <td className="text-end text-body-secondary">{t('retention.rows', { n: formatNumber(x.rows) })}</td></tr>)}
              </tbody></table>
            </div></div></div>
            <div className="col-lg-6"><div className="card mb-3"><div className="card-body"><h2 className="h6">{t('health.storage')}</h2>
              <Pairs rows={[[t('health.captureDir'), h.storage.captureDirectory],
                [t('health.diskUsed'), h.storage.diskUsedPercent != null ? `${h.storage.diskUsedPercent.toFixed(1)} %` : null],
                [t('health.diskFree'), h.storage.diskFreeBytes != null ? units.bytes(h.storage.diskFreeBytes) : null],
                [t('health.captureUse'), `${units.bytes(h.storage.captureUsedBytes)} / ${units.bytes(h.storage.captureQuotaBytes)}`]]} />
            </div></div>
            <div className="card mb-3"><div className="card-body"><h2 className="h6">{t('health.agentsJobs')}</h2>
              <Pairs rows={[[t('health.agentsReporting'), `${h.agents.reporting} / ${h.agents.active}`],
                [t('health.agentVersions'), (h.agents.versions || []).map((v) => `${v.version || '?'} ×${v.n}`).join(', ')],
                [t('health.retentionRun'), h.jobs.retentionLastRun], [t('health.pendingTasks'), h.jobs.pendingAgentTasks],
                [t('health.flowCollector'), h.jobs.flowCollectorEnabled ? (h.jobs.flowCollectorRunning ? t('health.running') : t('health.notRunning')) : t('health.off')]]} />
            </div></div>
            <div className="card"><div className="card-body"><h2 className="h6">{t('health.runtime')}</h2>
              <Pairs rows={[[t('health.version'), h.runtime.version], [t('health.java'), h.runtime.javaVersion],
                [t('health.uptime'), formatDuration(h.runtime.uptimeSeconds)],
                [t('health.heap'), `${units.bytes(h.runtime.heapUsedBytes)} / ${units.bytes(h.runtime.heapMaxBytes)}`],
                [t('health.timeZone'), h.runtime.timeZone]]} />
            </div></div></div>
          </div>
          <div className="card mt-3"><div className="card-body"><h2 className="h6">{t('health.configuration')}</h2>
            <p className="small text-body-secondary">{t('health.configNote')}</p>
            <Pairs rows={Object.entries(h.configuration).map(([k, v]) => [k, Array.isArray(v) ? v.join(', ') : String(v)])} />
          </div></div>
        </>
      )}
    </>
  );
}
