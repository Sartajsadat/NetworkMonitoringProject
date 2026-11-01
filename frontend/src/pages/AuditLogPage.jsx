import { Fragment, useState } from 'react';
import { auditApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import Pagination from '../components/Pagination.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import EmptyState from '../components/EmptyState.jsx';
import { formatDateTime } from '../components/TimeAgo.jsx';

const EMPTY = { from: '', to: '', actor: '', action: '', result: '' };

// <input type="datetime-local"> gives local time without zone; convert to UTC ISO for the API.
const toIso = (local) => (local ? new Date(local).toISOString() : undefined);

export default function AuditLogPage() {
  const [filters, setFilters] = useState(EMPTY);
  const [applied, setApplied] = useState(EMPTY);
  const [page, setPage] = useState(0);
  const [expanded, setExpanded] = useState(null);
  const { data: actions } = useApi(() => auditApi.actions(), []);
  const { data, error, loading, reload } = useApi(() => auditApi.search({
    from: toIso(applied.from), to: toIso(applied.to), actor: applied.actor || undefined,
    action: applied.action || undefined, result: applied.result || undefined, page, size: 50,
  }), [JSON.stringify(applied), page]);

  const set = (k) => (e) => setFilters({ ...filters, [k]: e.target.value });

  return (
    <>
      <PageHeader title="Audit log" subtitle="Append-only record of sensitive actions. Entries cannot be edited or deleted."
        breadcrumbs={[{ label: 'Audit log' }]} />
      <form className="card card-body mb-3" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(filters); }} aria-label="Audit filters">
        <div className="row g-2 align-items-end">
          <div className="col-md-3"><label htmlFor="a-from" className="form-label small mb-1">From (local time)</label>
            <input id="a-from" type="datetime-local" className="form-control form-control-sm" value={filters.from} onChange={set('from')} /></div>
          <div className="col-md-3"><label htmlFor="a-to" className="form-label small mb-1">To (local time)</label>
            <input id="a-to" type="datetime-local" className="form-control form-control-sm" value={filters.to} onChange={set('to')} /></div>
          <div className="col-md-2"><label htmlFor="a-actor" className="form-label small mb-1">User</label>
            <input id="a-actor" className="form-control form-control-sm" value={filters.actor} onChange={set('actor')} /></div>
          <div className="col-md-2"><label htmlFor="a-action" className="form-label small mb-1">Action</label>
            <select id="a-action" className="form-select form-select-sm" value={filters.action} onChange={set('action')}>
              <option value="">All</option>{(actions || []).map((a) => <option key={a} value={a}>{a}</option>)}
            </select></div>
          <div className="col-md-2"><label htmlFor="a-result" className="form-label small mb-1">Result</label>
            <select id="a-result" className="form-select form-select-sm" value={filters.result} onChange={set('result')}>
              <option value="">All</option><option value="SUCCESS">Success</option><option value="FAILURE">Failure</option><option value="DENIED">Denied</option>
            </select></div>
        </div>
        <div className="mt-2 d-flex gap-2">
          <button type="submit" className="btn btn-sm btn-primary">Apply</button>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => { setFilters(EMPTY); setApplied(EMPTY); setPage(0); }}>Clear</button>
        </div>
      </form>

      <ErrorAlert error={error} onRetry={reload} />
      {loading && !data && <LoadingSpinner />}
      {data && data.totalElements === 0 && <div className="card"><EmptyState icon="shield-check" title="No audit entries match" /></div>}
      {data && data.totalElements > 0 && (
        <div className="card">
          <div className="table-responsive">
            <table className="table table-sm mb-0 nm-table">
              <thead><tr><th>Time</th><th>User</th><th>Action</th><th>Target</th><th>Result</th><th>Source IP</th><th /></tr></thead>
              <tbody>
                {data.content.map((e) => (
                  <Fragment key={e.id}>
                    <tr>
                      <td className="small text-nowrap">{formatDateTime(e.occurredAt)}</td>
                      <td className="small">{e.actorUsername || '—'}{e.actorRoles && <div className="text-body-secondary" style={{ fontSize: '.75rem' }}>{e.actorRoles}</div>}</td>
                      <td className="small font-monospace">{e.action}</td>
                      <td className="small">{e.targetType ? `${e.targetType} ${e.targetId ?? ''}` : '—'}</td>
                      <td><StatusBadge status={e.result} /></td>
                      <td className="small font-monospace">{e.sourceIp || '—'}</td>
                      <td>
                        <button type="button" className="btn btn-sm btn-link" aria-expanded={expanded === e.id}
                          onClick={() => setExpanded(expanded === e.id ? null : e.id)}>
                          {expanded === e.id ? 'Hide' : 'Details'}
                        </button>
                      </td>
                    </tr>
                    {expanded === e.id && (
                      <tr><td colSpan={7} className="bg-body-tertiary small">
                        <div><strong>Request ID:</strong> <code>{e.requestId || '—'}</code></div>
                        {e.reason && <div><strong>Reason:</strong> {e.reason}</div>}
                        {e.details && <pre className="mb-0 mt-1 small">{JSON.stringify(e.details, null, 2)}</pre>}
                      </td></tr>
                    )}
                  </Fragment>
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
