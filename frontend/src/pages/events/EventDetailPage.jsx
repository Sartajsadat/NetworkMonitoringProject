import { Link, useParams } from 'react-router-dom';
import { eventApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import MetricChart from '../../components/MetricChart.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import SeverityBadge from '../../components/SeverityBadge.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import Coverage from './Coverage.jsx';
import EventList from './EventList.jsx';
import { CATEGORY_ICON, eventSummary, eventTitle } from './eventText.js';

function DetailValue({ value }) {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'object') return <code className="nm-ltr" dir="ltr">{JSON.stringify(value)}</code>;
  return <span className="nm-ltr">{String(value)}</span>;
}

export default function EventDetailPage() {
  const { id } = useParams();
  const i18n = useI18n();
  const { t, formatDateTime } = i18n;
  const { data, error, loading, reload } = useApi(() => eventApi.context(id), [id]);
  if (!data && loading) return <LoadingSpinner />;
  if (!data) return <ErrorAlert error={error} onRetry={reload} />;
  const e = data.event;
  const range = { from: data.windowFrom, to: data.windowTo };
  const details = Object.entries(e.details || {});
  return (
    <>
      <PageHeader
        title={<span className="d-inline-flex align-items-center gap-2 flex-wrap">{eventTitle(e, t)} <SeverityBadge severity={e.severity} /></span>}
        subtitle={<>{formatDateTime(e.occurredAt)}{e.serverName && <> · {e.serverName}</>}</>}
        breadcrumbs={[{ label: t('timeline.title'), to: '/timeline' }, { label: eventTitle(e, t) }]}
        actions={e.serverId && (
          <Link className="btn btn-sm btn-outline-primary"
            to={`/timeline?serverId=${e.serverId}&from=${encodeURIComponent(data.windowFrom)}&to=${encodeURIComponent(data.windowTo)}&order=asc`}>
            <i className="bi bi-clock-history me-1" aria-hidden="true" />{t('event.serverAround')}
          </Link>
        )}
      />
      <div className="row g-3">
        <div className="col-xl-5">
          <div className="card mb-3"><div className="card-body">
            <p className="mb-3">{eventSummary(e, i18n)}</p>
            <dl className="row small mb-0">
              <dt className="col-5">{t('event.category')}</dt>
              <dd className="col-7"><i className={`bi bi-${CATEGORY_ICON[e.category]} me-1`} aria-hidden="true" />{t(`category.${e.category}`)}</dd>
              <dt className="col-5">{t('event.time')}</dt><dd className="col-7">{formatDateTime(e.occurredAt)}</dd>
              <dt className="col-5">{t('event.received')}</dt><dd className="col-7">{formatDateTime(e.receivedAt)}</dd>
              <dt className="col-5">{t('event.source')}</dt><dd className="col-7">{t(`event.source.${e.source}`)}</dd>
              {e.serverName && <><dt className="col-5">{t('timeline.server')}</dt>
                <dd className="col-7">{e.serverId ? <Link to={`/servers/${e.serverId}`}>{e.serverName}</Link> : e.serverName}</dd></>}
              {e.resource && <><dt className="col-5">{t('event.resource')}</dt><dd className="col-7"><code className="nm-ltr" dir="ltr">{e.resource}</code></dd></>}
              {e.ipAddress && <><dt className="col-5">{t('timeline.ip')}</dt><dd className="col-7"><code className="nm-ltr" dir="ltr">{e.ipAddress}</code></dd></>}
              {data.alert && <><dt className="col-5">{t('event.alert')}</dt>
                <dd className="col-7"><Link to={`/alerts/${data.alert.id}`}>{data.alert.ruleName}</Link>{' '}
                  <StatusBadge status={data.alert.status === 'RESOLVED' ? 'SUCCESS' : 'DEGRADED'} label={t(`alertStatus.${data.alert.status}`)} /></dd></>}
              {e.captureId && <><dt className="col-5">{t('event.capture')}</dt>
                <dd className="col-7"><Link to={`/captures/${e.captureId}`}>{t('event.openCapture')}</Link></dd></>}
              {e.correlationId && <><dt className="col-5">{t('event.correlation')}</dt>
                <dd className="col-7"><Link className="nm-mono small" dir="ltr"
                  to={`/timeline?correlationId=${e.correlationId}&order=asc&from=${encodeURIComponent(new Date(new Date(e.occurredAt).getTime() - 7 * 864e5).toISOString())}&to=${encodeURIComponent(new Date().toISOString())}`}>
                  {t('event.allCorrelated')}</Link></dd></>}
            </dl>
          </div></div>
          {details.length > 0 && (
            <div className="card mb-3">
              <div className="card-header"><h2 className="h6 mb-0">{t('event.details')}</h2></div>
              <table className="table table-sm nm-table mb-0 small">
                <tbody>
                  {details.map(([k, v]) => <tr key={k}><th className="nm-mono fw-normal text-body-secondary" dir="ltr">{k}</th><td><DetailValue value={v} /></td></tr>)}
                </tbody>
              </table>
            </div>
          )}
          {e.serverId && (
            <div className="card mb-3">
              <div className="card-header"><h2 className="h6 mb-0">{t('event.captures')}</h2></div>
              <div className="card-body small">
                {data.capturesHidden ? <span className="text-body-secondary">{t('event.capturesHidden')}</span>
                  : data.captures.length === 0 ? <span className="text-body-secondary">{t('event.noCaptures')}</span> : (
                    <ul className="mb-0 ps-3">
                      {data.captures.map((c) => (
                        <li key={c.id}><Link to={`/captures/${c.id}`}>{c.name}</Link> — {t(`capture.status.${c.status}`)}
                          {!c.filesAvailable && <span className="text-body-secondary"> ({t('captures.filesDeleted')})</span>}</li>
                      ))}
                    </ul>
                  )}
              </div>
            </div>
          )}
        </div>
        <div className="col-xl-7">
          {e.serverId && (
            <div className="card mb-3">
              <div className="card-header"><h2 className="h6 mb-0">{t('event.metricsAround')}</h2></div>
              <div className="card-body">
                <Coverage coverage={data.coverage} />
                {data.coverage && data.coverage.samples > 0 && (
                  <div className="mt-3">
                    <MetricChart serverId={e.serverId} title={t('metric.cpuUsage')} range={range} unit="percent" yMax={100}
                      lines={[{ metric: 'cpu.usage_pct', label: () => t('metric.cpu') }, { metric: 'mem.used_pct', label: () => t('metric.memory') }]}
                      refreshMs={0} height={200} />
                  </div>
                )}
              </div>
            </div>
          )}
          <div className="card">
            <div className="card-header"><h2 className="h6 mb-0">{t('event.related')}</h2>
              <small className="text-body-secondary">{t('event.relatedHint')}</small></div>
            <EventList events={[...data.related, e].sort((a, b) => a.occurredAt.localeCompare(b.occurredAt) || a.id - b.id)} currentId={e.id} />
          </div>
        </div>
      </div>
    </>
  );
}
