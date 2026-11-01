import { Fragment, useState } from 'react';
import { auditApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useI18n } from '../i18n/I18nContext.jsx';
import EmptyState from '../components/EmptyState.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import Pagination from '../components/Pagination.jsx';
import StatusBadge from '../components/StatusBadge.jsx';

const EMPTY = { from: '', to: '', actor: '', action: '', result: '' };

// <input type="datetime-local"> gives local time without zone; convert to UTC ISO for the API.
const toIso = (local) => (local ? new Date(local).toISOString() : undefined);

export default function AuditLogPage() {
  const { t, formatDateTime } = useI18n();
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
      <PageHeader title={t('audit.title')} subtitle={t('audit.subtitle')} breadcrumbs={[{ label: t('audit.title') }]} />
      <form className="nm-filters mb-3" onSubmit={(e) => { e.preventDefault(); setPage(0); setApplied(filters); }} aria-label={t('audit.filters')}>
        <div className="row g-2 align-items-end">
          <div className="col-md-3"><label htmlFor="a-from" className="form-label small mb-1">{t('audit.from')}</label>
            <input id="a-from" type="datetime-local" dir="ltr" className="form-control form-control-sm" value={filters.from} onChange={set('from')} /></div>
          <div className="col-md-3"><label htmlFor="a-to" className="form-label small mb-1">{t('audit.to')}</label>
            <input id="a-to" type="datetime-local" dir="ltr" className="form-control form-control-sm" value={filters.to} onChange={set('to')} /></div>
          <div className="col-md-2"><label htmlFor="a-actor" className="form-label small mb-1">{t('audit.user')}</label>
            <input id="a-actor" dir="ltr" className="form-control form-control-sm nm-ltr" value={filters.actor} onChange={set('actor')} /></div>
          <div className="col-md-2"><label htmlFor="a-action" className="form-label small mb-1">{t('audit.action')}</label>
            <select id="a-action" className="form-select form-select-sm" value={filters.action} onChange={set('action')}>
              <option value="">{t('common.all')}</option>{(actions || []).map((a) => <option key={a} value={a}>{a}</option>)}
            </select></div>
          <div className="col-md-2"><label htmlFor="a-result" className="form-label small mb-1">{t('audit.result')}</label>
            <select id="a-result" className="form-select form-select-sm" value={filters.result} onChange={set('result')}>
              <option value="">{t('common.all')}</option>
              {['SUCCESS', 'FAILURE', 'DENIED'].map((r) => <option key={r} value={r}>{t(`status.${r}`)}</option>)}
            </select></div>
        </div>
        <div className="mt-2 d-flex gap-2">
          <button type="submit" className="btn btn-sm btn-primary">{t('common.apply')}</button>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => { setFilters(EMPTY); setApplied(EMPTY); setPage(0); }}>{t('common.clear')}</button>
        </div>
      </form>

      <ErrorAlert error={error} onRetry={reload} />
      {loading && !data && <LoadingSpinner />}
      {data && data.totalElements === 0 && <div className="card"><EmptyState icon="shield-check" title={t('audit.empty')} /></div>}
      {data && data.totalElements > 0 && (
        <div className="card">
          <div className="table-responsive">
            <table className="table table-sm nm-table">
              <thead><tr>
                <th>{t('audit.col.time')}</th><th>{t('audit.col.user')}</th><th>{t('audit.col.action')}</th>
                <th>{t('audit.col.target')}</th><th>{t('audit.col.result')}</th><th>{t('audit.col.sourceIp')}</th><th />
              </tr></thead>
              <tbody>
                {data.content.map((e) => (
                  <Fragment key={e.id}>
                    <tr>
                      <td className="small text-nowrap"><time dateTime={e.occurredAt} title={e.occurredAt}>{formatDateTime(e.occurredAt)}</time></td>
                      <td className="small"><span className="nm-ltr">{e.actorUsername || '—'}</span>
                        {e.actorRoles && <div className="text-body-secondary" style={{ fontSize: '.75rem' }}>{e.actorRoles.split(',').map((r) => t(`role.${r}`)).join(' · ')}</div>}
                      </td>
                      <td className="small nm-mono"><span className="nm-ltr">{e.action}</span></td>
                      <td className="small"><span className="nm-ltr">{e.targetType ? `${e.targetType} ${e.targetId ?? ''}` : '—'}</span></td>
                      <td><StatusBadge status={e.result} /></td>
                      <td className="small nm-mono"><span className="nm-ltr">{e.sourceIp || '—'}</span></td>
                      <td>
                        <button type="button" className="btn btn-sm btn-link" aria-expanded={expanded === e.id}
                          onClick={() => setExpanded(expanded === e.id ? null : e.id)}>
                          {expanded === e.id ? t('common.hide') : t('common.details')}
                        </button>
                      </td>
                    </tr>
                    {expanded === e.id && (
                      <tr><td colSpan={7} className="bg-body-tertiary small">
                        <div>{t('common.requestId')}: <code className="nm-ltr">{e.requestId || '—'}</code></div>
                        {e.reason && <div>{t('audit.reason')}: <span className="nm-ltr">{e.reason}</span></div>}
                        {e.details && <pre className="mb-0 mt-1 small" dir="ltr">{JSON.stringify(e.details, null, 2)}</pre>}
                      </td></tr>
                    )}
                  </Fragment>
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
