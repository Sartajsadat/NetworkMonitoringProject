import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { groupApi, serverApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { PERMISSIONS } from '../auth/roles.js';
import { useI18n } from '../i18n/I18nContext.jsx';
import EmptyState from '../components/EmptyState.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import Pagination from '../components/Pagination.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import TimeAgo from '../components/TimeAgo.jsx';
import { ENV_CODES, MONITORING_STATUSES, OS_CODES } from './labels.js';

const EMPTY_FILTERS = { search: '', environment: '', osFamily: '', organization: '', monitoringStatus: '', groupId: '' };

export default function ServersPage() {
  const { can } = useAuth();
  const { t } = useI18n();
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
  const [sortField, sortDir] = sort.split(',');
  const SortHeader = ({ field, label }) => (
    <th aria-sort={sortField === field ? (sortDir === 'asc' ? 'ascending' : 'descending') : undefined}>
      <button type="button" className="nm-sort"
        onClick={() => setSort(`${field},${sortField === field && sortDir === 'asc' ? 'desc' : 'asc'}`)}>
        {label}
        {sortField === field && (
          <i className={`bi bi-caret-${sortDir === 'asc' ? 'up' : 'down'}-fill small`}
            aria-label={t(sortDir === 'asc' ? 'servers.sortAsc' : 'servers.sortDesc')} />
        )}
      </button>
    </th>
  );

  return (
    <>
      <PageHeader
        title={t('servers.title')}
        subtitle={t('servers.subtitle')}
        breadcrumbs={[{ label: t('servers.title') }]}
        actions={can(PERMISSIONS.manageServers) && (
          <Link to="/servers/new" className="btn btn-primary btn-sm">
            <i className="bi bi-plus-lg me-1" aria-hidden="true" />{t('servers.register')}
          </Link>
        )}
      />

      <form className="nm-filters mb-3" onSubmit={apply} aria-label={t('servers.filters')}>
        <div className="row g-2 align-items-end">
          <div className="col-md-3">
            <label htmlFor="f-search" className="form-label small mb-1">{t('servers.search')}</label>
            <input id="f-search" className="form-control form-control-sm" placeholder={t('servers.searchPlaceholder')}
              value={filters.search} onChange={set('search')} />
          </div>
          <div className="col-6 col-md-2">
            <label htmlFor="f-env" className="form-label small mb-1">{t('servers.environment')}</label>
            <select id="f-env" className="form-select form-select-sm" value={filters.environment} onChange={set('environment')}>
              <option value="">{t('common.all')}</option>
              {ENV_CODES.map((k) => <option key={k} value={k}>{t(`env.${k}`)}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-2">
            <label htmlFor="f-os" className="form-label small mb-1">{t('servers.os')}</label>
            <select id="f-os" className="form-select form-select-sm" value={filters.osFamily} onChange={set('osFamily')}>
              <option value="">{t('common.all')}</option>
              {OS_CODES.map((k) => <option key={k} value={k}>{t(`os.${k}`)}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-2">
            <label htmlFor="f-org" className="form-label small mb-1">{t('servers.organization')}</label>
            <select id="f-org" className="form-select form-select-sm" value={filters.organization} onChange={set('organization')}>
              <option value="">{t('common.all')}</option>
              {(organizations || []).map((o) => <option key={o} value={o}>{o}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-1">
            <label htmlFor="f-status" className="form-label small mb-1">{t('servers.status')}</label>
            <select id="f-status" className="form-select form-select-sm" value={filters.monitoringStatus} onChange={set('monitoringStatus')}>
              <option value="">{t('common.all')}</option>
              {MONITORING_STATUSES.map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}
            </select>
          </div>
          <div className="col-6 col-md-2">
            <label htmlFor="f-group" className="form-label small mb-1">{t('servers.group')}</label>
            <select id="f-group" className="form-select form-select-sm" value={filters.groupId} onChange={set('groupId')}>
              <option value="">{t('common.all')}</option>
              {(groups || []).map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
            </select>
          </div>
        </div>
        <div className="mt-2 d-flex gap-2">
          <button type="submit" className="btn btn-sm btn-primary"><i className="bi bi-funnel me-1" aria-hidden="true" />{t('common.apply')}</button>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={clear}>{t('common.clear')}</button>
        </div>
      </form>

      <ErrorAlert error={error} onRetry={reload} />
      {loading && !data && <LoadingSpinner />}
      {data && data.totalElements === 0 && (
        <div className="card"><EmptyState icon="hdd-stack" title={t('servers.empty')}>
          {can(PERMISSIONS.manageServers) ? t('servers.emptyManage') : t('servers.emptyRead')}
        </EmptyState></div>
      )}
      {data && data.totalElements > 0 && (
        <div className="card">
          <div className="table-responsive">
            <table className="table table-hover nm-table">
              <thead>
                <tr>
                  <SortHeader field="name" label={t('servers.col.name')} />
                  <SortHeader field="hostname" label={t('servers.col.hostname')} />
                  <th>{t('servers.col.ips')}</th>
                  <th>{t('servers.col.os')}</th>
                  <SortHeader field="environment" label={t('servers.col.environment')} />
                  <SortHeader field="organization" label={t('servers.col.organization')} />
                  <th>{t('servers.col.monitoring')}</th>
                  <th>{t('servers.col.connection')}</th>
                  <th>{t('servers.col.lastSeen')}</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((s) => (
                  <tr key={s.id} className="nm-clickable" onClick={() => navigate(`/servers/${s.id}`)}>
                    <td><Link to={`/servers/${s.id}`} onClick={(e) => e.stopPropagation()}>{s.name}</Link></td>
                    <td className="nm-mono small"><span className="nm-ltr">{s.hostname}</span></td>
                    <td className="nm-mono small"><span className="nm-ltr">{s.ipAddresses.map((i) => i.address).join(', ') || '—'}</span></td>
                    <td>{t(`os.${s.osFamily}`)}</td>
                    <td>{t(`env.${s.environment}`)}</td>
                    <td>{s.organization}</td>
                    <td><StatusBadge status={s.monitoringStatus} /></td>
                    <td><StatusBadge status={s.connectionStatus} /></td>
                    <td className="small"><TimeAgo value={s.lastSeenAt} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="card-footer"><Pagination data={data} onPage={setPage} /></div>
        </div>
      )}
    </>
  );
}
