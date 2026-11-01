import { Link, useParams } from 'react-router-dom';
import { alertApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import MetricChart from '../../components/MetricChart.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import SeverityBadge from '../../components/SeverityBadge.jsx';
import Coverage from '../events/Coverage.jsx';
import EventList from '../events/EventList.jsx';
import { alertCondition, formatAlertValue, windowText } from '../events/eventText.js';
import AlertActions from './AlertActions.jsx';
import { alertStatusBadge } from './AlertsPage.jsx';

/** Which metric chart explains an alert type (none for non-metric alerts). */
const CHART = {
  CPU_HIGH: { metric: 'cpu.usage_pct', unit: 'percent', yMax: 100, label: 'metric.cpuUsage' },
  MEMORY_HIGH: { metric: 'mem.used_pct', unit: 'percent', yMax: 100, label: 'metric.memory' },
  SWAP_HIGH: { metric: 'swap.used_pct', unit: 'percent', yMax: 100, label: 'metric.swap' },
  DISK_USAGE_HIGH: { metric: 'disk.used_pct', unit: 'percent', yMax: 100, label: 'metric.diskFullest', perResource: true },
  NETWORK_THROUGHPUT_HIGH: { metric: 'net.rx_bps', metric2: 'net.tx_bps', l1: 'metric.in', l2: 'metric.out', unit: 'bits_per_second', label: 'metric.throughput', perResource: true },
  TRAFFIC_SPIKE: { metric: 'net.rx_bps', metric2: 'net.tx_bps', l1: 'metric.in', l2: 'metric.out', unit: 'bits_per_second', label: 'metric.throughput', perResource: true },
  INTERFACE_ERRORS: { metric: 'net.errors_ps', metric2: 'net.drops_ps', l1: 'metric.errors', l2: 'metric.drops', unit: 'per_second', label: 'alertType.INTERFACE_ERRORS', perResource: true },
};

export default function AlertDetailPage() {
  const { id } = useParams();
  const i18n = useI18n();
  const { t, formatDateTime, formatNumber } = i18n;
  const { can } = useAuth();
  const { data, error, loading, reload } = useApi(() => alertApi.get(id), [id]);
  if (!data && loading) return <LoadingSpinner />;
  if (!data) return <ErrorAlert error={error} onRetry={reload} />;
  const a = data.alert;
  const chart = CHART[a.ruleType];
  const fv = (v) => formatAlertValue(a.unit, v, i18n);
  return (
    <>
      <PageHeader
        title={<span className="d-inline-flex align-items-center gap-2 flex-wrap">{a.ruleName} <SeverityBadge severity={a.severity} /> {alertStatusBadge(a.status, t)}</span>}
        subtitle={<>{a.serverName || t('alerts.platform')}{a.resource && <> · <code className="nm-ltr" dir="ltr">{a.resource}</code></>}</>}
        breadcrumbs={[{ label: t('alerts.title'), to: '/alerts' }, { label: a.ruleName }]}
        actions={can(PERMISSIONS.operateAlerts) && <AlertActions alert={a} onChanged={reload} />}
      />
      <div className="row g-3">
        <div className="col-xl-5">
          <div className="card mb-3"><div className="card-body">
            <p className="fw-semibold">{alertCondition(a, i18n)}</p>
            <dl className="row small mb-0">
              <dt className="col-5">{t('alert.type')}</dt><dd className="col-7">{t(`alertType.${a.ruleType}`)}</dd>
              <dt className="col-5">{t('alert.threshold')}</dt><dd className="col-7">{a.threshold == null ? '—' : fv(a.threshold)}</dd>
              <dt className="col-5">{t('alert.window')}</dt><dd className="col-7">{windowText(a.windowSeconds, i18n)}</dd>
              <dt className="col-5">{t('alert.lastValue')}</dt><dd className="col-7">{fv(a.lastValue)}</dd>
              <dt className="col-5">{t('alert.peakValue')}</dt><dd className="col-7">{fv(a.peakValue)}</dd>
              <dt className="col-5">{t('alerts.first')}</dt><dd className="col-7">{formatDateTime(a.firstTriggeredAt)}</dd>
              <dt className="col-5">{t('alerts.latest')}</dt><dd className="col-7">{formatDateTime(a.lastTriggeredAt)}</dd>
              <dt className="col-5">{t('alert.evaluations')}</dt><dd className="col-7">{formatNumber(a.evaluations)}</dd>
              {a.acknowledgedAt && <><dt className="col-5">{t('alertStatus.ACKNOWLEDGED')}</dt>
                <dd className="col-7">{a.acknowledgedBy} · {formatDateTime(a.acknowledgedAt)}{a.ackComment && <div className="text-body-secondary">{a.ackComment}</div>}</dd></>}
              {a.resolvedAt && <><dt className="col-5">{t('alertStatus.RESOLVED')}</dt>
                <dd className="col-7">{a.resolvedBy || t('alert.automatically')} · {formatDateTime(a.resolvedAt)}
                  <div className="text-body-secondary">{a.resolutionType === 'MANUAL' && a.resolution ? a.resolution : t(`alert.resolution.${a.resolutionType}`)}</div></dd></>}
              {data.rule && <><dt className="col-5">{t('alert.rule')}</dt><dd className="col-7"><Link to="/alerts/rules">{data.rule.name}</Link></dd></>}
              {a.serverId && <><dt className="col-5">{t('timeline.server')}</dt><dd className="col-7"><Link to={`/servers/${a.serverId}`}>{a.serverName}</Link></dd></>}
            </dl>
          </div></div>
        </div>
        <div className="col-xl-7">
          {a.serverId && (
            <div className="card mb-3">
              <div className="card-header"><h2 className="h6 mb-0">{t('event.metricsAround')}</h2></div>
              <div className="card-body">
                <Coverage coverage={data.coverage} />
                {chart && data.coverage?.samples > 0 && (
                  <div className="mt-3">
                    <MetricChart serverId={a.serverId} title={t(chart.label)} range={{ from: data.windowFrom, to: data.windowTo }}
                      unit={chart.unit} yMax={chart.yMax} resource={chart.perResource ? a.resource : undefined} refreshMs={0} height={200}
                      lines={[{ metric: chart.metric, label: () => t(chart.l1 || chart.label) }, ...(chart.metric2 ? [{ metric: chart.metric2, label: () => t(chart.l2) }] : [])]} />
                  </div>
                )}
              </div>
            </div>
          )}
          <div className="card">
            <div className="card-header"><h2 className="h6 mb-0">{t('alert.events')}</h2>
              <small className="text-body-secondary">{t('alert.eventsHint')}</small></div>
            <EventList events={data.events} />
          </div>
        </div>
      </div>
    </>
  );
}
