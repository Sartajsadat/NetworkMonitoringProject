import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { dashboardApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { PERMISSIONS } from '../auth/roles.js';
import { useI18n } from '../i18n/I18nContext.jsx';
import CountBarChart from '../components/CountBarChart.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import TimeAgo from '../components/TimeAgo.jsx';
import { toChartData } from './labels.js';

const REFRESH_OPTIONS = [
  { value: 0, label: 'dashboard.refreshOff' },
  { value: 30, label: 'dashboard.refresh30s' },
  { value: 60, label: 'dashboard.refresh1m' },
  { value: 300, label: 'dashboard.refresh5m' },
];

function StatTile({ label, value, icon, hint }) {
  const { formatNumber } = useI18n();
  return (
    <div className="card h-100 nm-stat">
      <div className="card-body">
        <div className="d-flex justify-content-between gap-2">
          <div className="nm-stat-label">{label}</div>
          <i className={`bi bi-${icon} text-body-secondary`} aria-hidden="true" />
        </div>
        <div className="nm-stat-value">{formatNumber(value)}</div>
        {hint && <div className="small text-body-secondary">{hint}</div>}
      </div>
    </div>
  );
}

/** Module name/message in the user's language, falling back to the server's English text. */
function moduleText(t, m, field, vars) {
  if (field === 'message') {
    const specific = `module.${m.key}.message.${m.availability}`;
    const tr = t(specific, vars);
    if (tr !== specific) return tr;
  }
  const key = `module.${m.key}.${field}`;
  const translated = m.key ? t(key) : key;
  return translated === key ? (field === 'name' ? m.module : m.message) : translated;
}

function FleetTile({ label, value, hint, icon }) {
  return (
    <div className="card h-100 nm-stat">
      <div className="card-body">
        <div className="d-flex justify-content-between gap-2">
          <div className="nm-stat-label">{label}</div>
          <i className={`bi bi-${icon} text-body-secondary`} aria-hidden="true" />
        </div>
        <div className="nm-stat-value" style={{ fontSize: '1.6rem' }}>{value}</div>
        {hint && <div className="small text-body-secondary">{hint}</div>}
      </div>
    </div>
  );
}

export default function DashboardPage() {
  const { t, units, formatNumber } = useI18n();
  const { can } = useAuth();
  const [refreshSeconds, setRefreshSeconds] = useState(60);
  const { data, error, loading, reload } = useApi(() => dashboardApi.summary(), []);

  useEffect(() => {
    if (!refreshSeconds) return undefined;
    const timer = setInterval(reload, refreshSeconds * 1000);
    return () => clearInterval(timer);
  }, [refreshSeconds, reload]);

  return (
    <>
      <div className="d-flex justify-content-between align-items-start flex-wrap gap-2 mb-3">
        <div>
          <h1 className="h4 mb-0">{t('dashboard.title')}</h1>
          <div className="small text-body-secondary">
            {data ? <>{t('dashboard.dataAsOf')} <TimeAgo value={data.generatedAt} /></> : t('common.loading')}
          </div>
        </div>
        <div className="d-flex align-items-center gap-2">
          <label htmlFor="refresh" className="small text-body-secondary text-nowrap">{t('dashboard.autoRefresh')}</label>
          <select id="refresh" className="form-select form-select-sm" style={{ width: 'auto' }} value={refreshSeconds}
            onChange={(e) => setRefreshSeconds(Number(e.target.value))}>
            {REFRESH_OPTIONS.map((o) => <option key={o.value} value={o.value}>{t(o.label)}</option>)}
          </select>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={reload} disabled={loading}
            aria-label={t('dashboard.refreshNow')} title={t('dashboard.refreshNow')}>
            <i className="bi bi-arrow-clockwise" aria-hidden="true" />
          </button>
        </div>
      </div>

      <ErrorAlert error={error} onRetry={reload} />
      {!data && loading && <LoadingSpinner />}

      {data && (
        <>
          <div className="row g-3 mb-3">
            <div className="col-6 col-md-4 col-xl"><StatTile label={t('dashboard.tile.servers')} value={data.totalServers} icon="hdd-stack" /></div>
            <div className="col-6 col-md-4 col-xl"><StatTile label={t('dashboard.tile.online')} value={data.connection.ONLINE} icon="check-circle" hint={t('dashboard.tile.onlineHint')} /></div>
            <div className="col-6 col-md-4 col-xl"><StatTile label={t('dashboard.tile.offline')} value={data.connection.OFFLINE} icon="x-octagon" hint={t('dashboard.tile.offlineHint')} /></div>
            <div className="col-6 col-md-4 col-xl"><StatTile label={t('dashboard.tile.noAgent')} value={data.connection.NO_AGENT} icon="dash-circle" hint={t('dashboard.tile.noAgentHint')} /></div>
            <div className="col-6 col-md-4 col-xl"><StatTile label={t('dashboard.tile.groups')} value={data.serverGroups} icon="collection" /></div>
            <div className="col-6 col-md-4 col-xl"><StatTile label={t('dashboard.tile.users')} value={data.activeUsers} icon="people" /></div>
            {can(PERMISSIONS.viewCaptures) && (
              <div className="col-6 col-md-4 col-xl">
                <Link to="/captures" className="text-reset text-decoration-none d-block h-100">
                  <StatTile label={t('dashboard.tile.activeCaptures')} value={data.activeCaptures ?? 0} icon="record-circle"
                    hint={t('dashboard.tile.activeCapturesHint')} />
                </Link>
              </div>
            )}
          </div>

          <div className="row g-3 mb-3">
            <div className="col-12 d-flex justify-content-between align-items-center flex-wrap gap-2">
              <h2 className="h6 mb-0">{t('dashboard.liveTitle')}</h2>
              <small className="text-body-secondary">
                {data.fleet?.reportingServers
                  ? t('dashboard.reporting', { n: formatNumber(data.fleet.reportingServers) })
                  : t('dashboard.noneReporting')}
              </small>
            </div>
            <div className="col-6 col-xl-3">
              <FleetTile icon="cpu" label={t('dashboard.fleet.cpu')} value={units.percent(data.fleet?.avgCpuPercent)}
                hint={data.fleet?.maxCpuPercent != null ? t('dashboard.fleet.max', { v: units.percent(data.fleet.maxCpuPercent) }) : null} />
            </div>
            <div className="col-6 col-xl-3">
              <FleetTile icon="memory" label={t('dashboard.fleet.memory')} value={units.percent(data.fleet?.avgMemoryPercent)}
                hint={data.fleet?.maxMemoryPercent != null ? t('dashboard.fleet.max', { v: units.percent(data.fleet.maxMemoryPercent) }) : null} />
            </div>
            <div className="col-6 col-xl-3">
              <FleetTile icon="device-hdd" label={t('dashboard.fleet.disk')} value={units.percent(data.fleet?.maxDiskUsedPercent)} />
            </div>
            <div className="col-6 col-xl-3">
              <FleetTile icon="arrow-down-up" label={t('dashboard.fleet.network')}
                value={<span style={{ fontSize: '1.1rem' }}>↓ {units.bits(data.fleet?.totalRxBitsPerSec)}<br />↑ {units.bits(data.fleet?.totalTxBitsPerSec)}</span>}
                hint={t('metric.excludesLoopback')} />
            </div>
          </div>

          <div className="row g-3 mb-3">
            {[['dashboard.byOs', data.byOsFamily, 'os'], ['dashboard.byEnv', data.byEnvironment, 'env']].map(([title, map, prefix]) => (
              <div className="col-lg-6" key={title}>
                <div className="card h-100"><div className="card-body">
                  <h2 className="h6">{t(title)}</h2>
                  {data.totalServers === 0 ? (
                    <div className="small text-body-secondary py-3">
                      {t('dashboard.noServers')} {prefix === 'os' && <Link to="/servers">{t('dashboard.registerLink')}</Link>}
                    </div>
                  ) : (
                    <CountBarChart title={t(title)} data={toChartData(map, prefix, t)} />
                  )}
                </div></div>
              </div>
            ))}
          </div>

          <div className="row g-3">
            <div className="col-lg-6">
              <div className="card h-100">
                <div className="card-header"><h2 className="h6 mb-0">{t('dashboard.modules')}</h2></div>
                <ul className="list-group list-group-flush">
                  {data.modules.map((m) => (
                    <li key={m.key || m.module} className="list-group-item">
                      <div className="d-flex justify-content-between align-items-center gap-2">
                        <span className="fw-semibold small">{moduleText(t, m, 'name')}</span>
                        <StatusBadge status={m.availability} />
                      </div>
                      <div className="small text-body-secondary">{moduleText(t, m, 'message', { n: formatNumber(data.fleet?.reportingServers ?? 0) })}</div>
                    </li>
                  ))}
                </ul>
                <div className="card-footer small text-body-secondary">{t('dashboard.modulesFooter')}</div>
              </div>
            </div>
            {data.recentSecurityEvents && (
              <div className="col-lg-6">
                <div className="card h-100">
                  <div className="card-header d-flex justify-content-between">
                    <h2 className="h6 mb-0">{t('dashboard.security')}</h2>
                    <Link to="/audit-logs" className="small">{t('dashboard.auditLink')}</Link>
                  </div>
                  {data.recentSecurityEvents.length === 0 ? (
                    <div className="card-body small text-body-secondary">{t('dashboard.noSecurityEvents')}</div>
                  ) : (
                    <div className="table-responsive">
                      <table className="table table-sm nm-table">
                        <thead><tr><th>{t('dashboard.col.when')}</th><th>{t('dashboard.col.action')}</th><th>{t('dashboard.col.user')}</th><th>{t('dashboard.col.result')}</th></tr></thead>
                        <tbody>
                          {data.recentSecurityEvents.map((e) => (
                            <tr key={e.id}>
                              <td className="small"><TimeAgo value={e.occurredAt} /></td>
                              <td className="small nm-mono nm-ltr">{e.action}</td>
                              <td className="small nm-ltr">{e.actorUsername || '—'}</td>
                              <td><StatusBadge status={e.result} /></td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        </>
      )}
    </>
  );
}
