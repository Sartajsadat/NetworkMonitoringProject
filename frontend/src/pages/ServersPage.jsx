import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { groupApi, serverApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { PERMISSIONS } from '../auth/roles.js';
import EmptyState from '../components/EmptyState.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import Pagination from '../components/Pagination.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import TimeAgo from '../components/TimeAgo.jsx';
import { ENV_LABELS, MONITORING_STATUSES, OS_LABELS } from './labels.js';

const EMPTY_FILTERS = { search: '', environment: '', osFamily: '', organization: '', monitoringStatus: '', groupId: '' };

export default function ServersPage() {
  const { can } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const initial = { ...EMPTY_FILTERS, groupId: searchParams.get('groupId') || '' };
  const [filters, setFilters] = useState(initial);
  const [applied, setApplied] = useState(initial);
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState('name,asc');

  const params = Object.fromEntries(Object.entries(applied).filter(([, v]) => v !== ''));
  const { data, error, loading, reload } = useApi(
    () => serverApi.search({ ...params, page, size: 25, sort }),
    [JSON.stringify(params), page, sort],
  );
  const { data: groups } = useApi(() => groupApi.list(), []);
  const { data: organizations } = useApi(() => serverApi.organizations(), []);

  const set = (k) => (e) => setFilters({ ...filters, [k]: e.target.value });
  const apply = (e) => {
    e.preventDefault();
    setPage(0);
    setApplied(filters);
  };
  const clear = () => {
    setFilters(EMPTY_FILTERS);
    setApplied(EMPTY_FILTERS);
    setPage(0);
  };
  const sortBy = (field) => {
    const [f, dir] = sort.split(',');
    setSort(`${field},${f === field && dir === 'asc' ? 'desc' : 'asc'}`);
  };
  const sortIcon = (field) => {
    const [f, dir] = sort.split(',');
    if (f !== field) return null;
    return <i className={`bi bi-caret-${dir === 'asc' ? 'up' : 'down'}-fill small`} aria-label={dir === 'asc' ? 'ascending' : 'descending'} />;
  };

  return (
    <>
      <PageHeader
        title="Server inventory"
        subtitle="Servers registered for monitoring. Connection status comes from monitoring agents (Phase 2)."
        breadcrumbs={[{ label: 'Server inventory' }]}
        actions={can(PERMISSIONS.manageServers) && (
          <Link to="/servers/new" className="btn btn-primary btn-sm">
            <i className="bi bi-plus-lg me-1" aria-hidden="true" />Register server
          </Link>
        )}
      />

      <form className="card card-body mb-3" onSubmit={apply} aria-label="Server filters">
        <div className="row g-2 align-items-end">
          <div className="col-md-3">
            <label htmlFor="f-search" className="form-label small mb-1">Search</label>
            <input id="f-search" className="form-control form-control-sm" placeholder="Name, hostname, IP, organization"
              value={filters.search} onChange={set('search')} />
          </div>
          <div className="col-6 col-md-2">
            <label htmlFor="f-env" className="form-label small mb-1">Environment</label>
            <select id="f-env" className="form-select form-select-sm" value={filters.environment} onChange={set('environment')}>
              <option value="">All</option>
              {Object.entries(ENV_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-2">
            <label htmlFor="f-os" className="form-label small mb-1">Operating system</label>
            <select id="f-os" className="form-select form-select-sm" value={filters.osFamily} onChange={set('osFamily')}>
              <option value="">All</option>
              {Object.entries(OS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-2">
            <label htmlFor="f-org" className="form-label small mb-1">Organization</label>
            <select id="f-org" className="form-select form-select-sm" value={filters.organization} onChange={set('organization')}>
              <option value="">All</option>
              {(organizations || []).map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-1">
            <label htmlFor="f-status" className="form-label small mb-1">Status</label>
            <select id="f-status" className="form-select form-select-sm" value={filters.monitoringStatus} onChange={set('monitoringStatus')}>
              <option value="">All</option>
              {MONITORING_STATUSES.map((s) => <option key={s} value={s}>{s.replace('_', ' ').toLowerCase()}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-2">
            <label htmlFor="f-group" className="form-label small mb-1">Group</label>
            <select id="f-group" className="form-select form-select-sm" value={filters.groupId} onChange={set('groupId')}>
              <option value="">All</option>
              {(groups || []).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
            </select>
          </div>
        </div>
        <div className="mt-2 d-flex gap-2">
          <button type="submit" className="btn btn-sm btn-primary"><i className="bi bi-funnel me-1" aria-hidden="true" />Apply</button>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={clear}>Clear</button>
        </div>
      </form>

      <ErrorAlert error={error} onRetry={reload} />
      {loading && !data && <LoadingSpinner />}
      {data && data.totalElements === 0 && (
        <div className="card"><EmptyState icon="hdd-stack" title="No servers match">
          {can(PERMISSIONS.manageServers) ? 'Register a server or change the filters.' : 'Try changing the filters.'}
        </EmptyState></div>
      )}
      {data && data.totalElements > 0 && (
        <div className="card">
          <div className="table-responsive">
            <table className="table table-hover mb-0 nm-table">
              <thead>
                <tr>
                  <th><button type="button" className="btn btn-link p-0 text-reset fw-semibold text-decoration-none" onClick={() => sortBy('name')}>Name {sortIcon('name')}</button></th>
                  <th><button type="button" className="btn btn-link p-0 text-reset fw-semibold text-decoration-none" onClick={() => sortBy('hostname')}>Hostname {sortIcon('hostname')}</button></th>
                  <th>IP addresses</th>
                  <th>OS</th>
                  <th><button type="button" className="btn btn-link p-0 text-reset fw-semibold text-decoration-none" onClick={() => sortBy('environment')}>Environment {sortIcon('environment')}</button></th>
                  <th><button type="button" className="btn btn-link p-0 text-reset fw-semibold text-decoration-none" onClick={() => sortBy('organization')}>Organization {sortIcon('organization')}</button></th>
                  <th>Monitoring</th>
                  <th>Connection</th>
                  <th>Last seen</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((s) => (
                  <tr key={s.id} className="nm-clickable" onClick={() => navigate(`/servers/${s.id}`)}>
                    <td><Link to={`/servers/${s.id}`} onClick={(e) => e.stopPropagation()}>{s.name}</Link></td>
                    <td className="font-monospace small">{s.hostname}</td>
                    <td className="font-monospace small">{s.ipAddresses.map((i) => i.address).join(', ') || '—'}</td>
                    <td>{OS_LABELS[s.osFamily]}</td>
                    <td>{ENV_LABELS[s.environment]}</td>
                    <td>{s.organization}</td>
                    <td><StatusBadge status={s.monitoringStatus} /></td>
                    <td><StatusBadge status={s.connectionStatus} /></td>
                    <td className="small"><TimeAgo value={s.lastSeenAt} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="card-footer bg-body"><Pagination data={data} onPage={setPage} /></div>
        </div>
      )}
    </>
  );
}
