import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { captureApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Pagination from '../../components/Pagination.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import { ALL_STATES, durationText, isActive } from './captureHelpers.js';

export default function CapturesPage() {
  const i18n = useI18n();
  const { t, units, formatNumber, formatDateTime } = i18n;
  const { can } = useAuth();
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const { data, error, loading, reload } = useApi(
    () => captureApi.list({ status: status || undefined, search: query || undefined, page, size: 25 }),
    [status, query, page],
  );
  const limits = useApi(() => captureApi.limits(), []);
  const anyActive = data?.content?.some(isActive);

  useEffect(() => {
    if (!anyActive) return undefined;
    const timer = setInterval(reload, 5000);
    return () => clearInterval(timer);
  }, [anyActive, reload]);

  const l = limits.data;
  return (
    <>
      <PageHeader
        title={t('captures.title')}
        subtitle={t('captures.subtitle')}
        breadcrumbs={[{ label: t('captures.title') }]}
        actions={can(PERMISSIONS.manageCaptures) && (
          <Link to="/captures/new" className="btn btn-primary btn-sm">
            <i className="bi bi-record-circle me-1" aria-hidden="true" />{t('captures.new')}
          </Link>
        )}
      />
      {l && (
        <div className="card mb-3"><div className="card-body py-2 d-flex flex-wrap gap-4 align-items-center small">
          <div style={{ minWidth: 240 }} className="flex-grow-1">
            <div className="d-flex justify-content-between">
              <span>{t('captures.storage')}</span>
              <span className="nm-num">{t('captures.storageOf', { used: units.bytes(l.usedBytes), total: units.bytes(l.quotaBytes) })}</span>
            </div>
            <div className="progress mt-1" style={{ height: 6 }} role="progressbar" aria-label={t('captures.storage')}
              aria-valuenow={l.quotaBytes ? Math.round((l.usedBytes / l.quotaBytes) * 100) : 0} aria-valuemin={0} aria-valuemax={100}>
              <div className="progress-bar" style={{ width: `${l.quotaBytes ? Math.max(0.5, (l.usedBytes / l.quotaBytes) * 100) : 0}%` }} />
            </div>
          </div>
          <div>{t('captures.retentionDefault', { n: formatNumber(l.defaultRetentionDays) })}</div>
          {!l.analysisAvailable && <div className="text-danger"><i className="bi bi-exclamation-triangle me-1" />{t('captures.noTshark')}</div>}
        </div></div>
      )}

      <form className="row g-2 mb-3" onSubmit={(e) => { e.preventDefault(); setPage(0); setQuery(search.trim()); }}>
        <div className="col-sm-6 col-lg-4">
          <input className="form-control form-control-sm" value={search} maxLength={120} onChange={(e) => setSearch(e.target.value)}
            placeholder={t('captures.searchPlaceholder')} aria-label={t('common.search')} />
        </div>
        <div className="col-sm-4 col-lg-3">
          <select className="form-select form-select-sm" value={status} aria-label={t('captures.col.status')}
            onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
            <option value="">{t('captures.allStatuses')}</option>
            {ALL_STATES.map((s) => <option key={s} value={s}>{t(`capture.status.${s}`)}</option>)}
          </select>
        </div>
        <div className="col-auto"><button type="submit" className="btn btn-sm btn-outline-secondary">{t('common.search')}</button></div>
      </form>

      <ErrorAlert error={error} onRetry={reload} />
      {loading && !data && <LoadingSpinner />}
      {data && data.content.length === 0 && (
        <div className="card"><EmptyState icon="record-circle" title={t('captures.none')}>{t('captures.noneHint')}</EmptyState></div>
      )}
      {data && data.content.length > 0 && (
        <div className="card">
          <div className="table-responsive">
            <table className="table nm-table mb-0">
              <thead><tr>
                <th>{t('captures.col.name')}</th><th>{t('captures.col.server')}</th><th>{t('captures.col.interface')}</th>
                <th>{t('captures.col.status')}</th><th className="text-end">{t('captures.col.packets')}</th>
                <th className="text-end">{t('captures.col.size')}</th><th>{t('captures.col.requested')}</th><th>{t('captures.col.files')}</th>
              </tr></thead>
              <tbody>
                {data.content.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <Link to={`/captures/${c.id}`} className="fw-semibold">{c.name}</Link>
                      {c.reference && <div className="small text-body-secondary nm-ltr">{c.reference}</div>}
                    </td>
                    <td className="small">{c.serverName}</td>
                    <td className="small">
                      <span className="nm-mono nm-ltr">{c.interfaceName}</span>
                      {c.captureFilter && <div className="nm-mono small text-body-secondary text-truncate" style={{ maxWidth: 220 }} dir="ltr" title={c.captureFilter}>{c.captureFilter}</div>}
                    </td>
                    <td>
                      <StatusBadge status={c.status} label={t(`capture.status.${c.status}`)} />
                      <div className="small text-body-secondary">{durationText(c.durationSeconds, i18n)}</div>
                    </td>
                    <td className="text-end nm-num">{formatNumber(c.packetsIndexed)}</td>
                    <td className="text-end nm-num text-nowrap">{units.bytes(c.bytesStored)}</td>
                    <td className="small">{c.requestedBy}<div className="text-body-secondary"><TimeAgo value={c.requestedAt} /></div></td>
                    <td className="small">
                      {c.filesAvailable
                        ? <>{t('captures.expires')} {formatDateTime(c.expiresAt)}</>
                        : <span className="text-body-secondary">{t('captures.filesDeleted')}</span>}
                    </td>
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
