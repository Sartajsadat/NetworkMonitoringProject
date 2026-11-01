import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { dashboardApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import CountBarChart from '../components/CountBarChart.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import TimeAgo from '../components/TimeAgo.jsx';
import { ENV_LABELS, OS_LABELS, toChartData } from './labels.js';

const REFRESH_OPTIONS = [
  { value: 0, label: 'Off' },
  { value: 30, label: '30 s' },
  { value: 60, label: '1 min' },
  { value: 300, label: '5 min' },
];

function StatTile({ label, value, icon, hint }) {
  return (
    <div className="card h-100 nm-stat">
      <div className="card-body">
        <div className="d-flex justify-content-between">
          <div className="nm-stat-label">{label}</div>
          <i className={`bi bi-${icon} text-body-secondary`} aria-hidden="true" />
        </div>
        <div className="nm-stat-value">{value}</div>
        {hint && <div className="small text-body-secondary">{hint}</div>}
      </div>
    </div>
  );
}

export default function DashboardPage() {
  const [refreshSeconds, setRefreshSeconds] = useState(60);
  const { data, error, loading, reload } = useApi(() => dashboardApi.summary(), []);

  useEffect(() => {
    if (!refreshSeconds) return undefined;
    const t = setInterval(reload, refreshSeconds * 1000);
    return () => clearInterval(t);
  }, [refreshSeconds, reload]);

  return (
    <>
      <div className="d-flex justify-content-between align-items-start flex-wrap gap-2 mb-3">
        <div>
          <h1 className="h4 mb-0">Dashboard</h1>
          <div className="small text-body-secondary">
            {data ? <>Data as of <TimeAgo value={data.generatedAt} /></> : 'Loading…'}
          </div>
        </div>
        <div className="d-flex align-items-center gap-2">
          <label htmlFor="refresh" className="small text-body-secondary">Auto-refresh</label>
          <select id="refresh" className="form-select form-select-sm" style={{ width: 'auto' }} value={refreshSeconds}
            onChange={(e) => setRefreshSeconds(Number(e.target.value))}>
            {REFRESH_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={reload} disabled={loading} aria-label="Refresh now">
            <i className="bi bi-arrow-clockwise" aria-hidden="true" />
          </button>
        </div>
      </div>

      <ErrorAlert error={error} onRetry={reload} />
      {!data && loading && <LoadingSpinner />}

      {data && (
        <>
          <div className="row g-3 mb-3">
            <div className="col-6 col-xl-2"><StatTile label="Registered servers" value={data.totalServers} icon="hdd-stack" /></div>
            <div className="col-6 col-xl-2"><StatTile label="Online" value={data.connection.ONLINE} icon="check-circle" hint="Agent reported recently" /></div>
            <div className="col-6 col-xl-2"><StatTile label="Offline" value={data.connection.OFFLINE} icon="x-octagon" hint="Agent stopped reporting" /></div>
            <div className="col-6 col-xl-2"><StatTile label="No agent" value={data.connection.NO_AGENT} icon="dash-circle" hint="Never reported" /></div>
            <div className="col-6 col-xl-2"><StatTile label="Server groups" value={data.serverGroups} icon="collection" /></div>
            <div className="col-6 col-xl-2"><StatTile label="Active users" value={data.activeUsers} icon="people" /></div>
          </div>

          <div className="row g-3 mb-3">
            <div className="col-lg-6">
              <div className="card h-100"><div className="card-body">
                <h2 className="h6">Servers by operating system</h2>
                {data.totalServers === 0
                  ? <div className="small text-body-secondary py-3">No servers registered yet. <Link to="/servers">Register a server</Link>.</div>
                  : <CountBarChart title="Servers by operating system" data={toChartData(data.byOsFamily, OS_LABELS)} />}
              </div></div>
            </div>
            <div className="col-lg-6">
              <div className="card h-100"><div className="card-body">
                <h2 className="h6">Servers by environment</h2>
                {data.totalServers === 0
                  ? <div className="small text-body-secondary py-3">No servers registered yet.</div>
                  : <CountBarChart title="Servers by environment" data={toChartData(data.byEnvironment, ENV_LABELS)} />}
              </div></div>
            </div>
          </div>

          <div className="row g-3">
            <div className="col-lg-6">
              <div className="card h-100">
                <div className="card-header bg-body"><h2 className="h6 mb-0">Telemetry modules</h2></div>
                <ul className="list-group list-group-flush">
                  {data.modules.map((m) => (
                    <li key={m.module} className="list-group-item">
                      <div className="d-flex justify-content-between align-items-center gap-2">
                        <span className="fw-semibold small">{m.module}</span>
                        <StatusBadge status={m.availability} />
                      </div>
                      <div className="small text-body-secondary">{m.message}</div>
                    </li>
                  ))}
                </ul>
                <div className="card-footer small text-body-secondary bg-body">
                  CPU, memory, disk and network charts appear here once agents report real data. No placeholder numbers are shown.
                </div>
              </div>
            </div>
            {data.recentSecurityEvents && (
              <div className="col-lg-6">
                <div className="card h-100">
                  <div className="card-header bg-body d-flex justify-content-between">
                    <h2 className="h6 mb-0">Recent security events</h2>
                    <Link to="/audit-logs" className="small">Audit log</Link>
                  </div>
                  {data.recentSecurityEvents.length === 0 ? (
                    <div className="card-body small text-body-secondary">No failed or denied actions recorded.</div>
                  ) : (
                    <div className="table-responsive">
                      <table className="table table-sm mb-0 nm-table">
                        <thead><tr><th>When</th><th>Action</th><th>User</th><th>Result</th></tr></thead>
                        <tbody>
                          {data.recentSecurityEvents.map((e) => (
                            <tr key={e.id}>
                              <td className="small"><TimeAgo value={e.occurredAt} /></td>
                              <td className="small font-monospace">{e.action}</td>
                              <td className="small">{e.actorUsername || '—'}</td>
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
