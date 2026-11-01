import { useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { alertApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Pagination from '../../components/Pagination.jsx';
import SeverityBadge from '../../components/SeverityBadge.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import { SEVERITIES, alertCondition } from '../events/eventText.js';
import AlertActions from './AlertActions.jsx';

const TABS = ['ACTIVE', 'RESOLVED', 'ALL'];

export function alertStatusBadge(status, t) {
  const map = { OPEN: 'FAILED', ACKNOWLEDGED: 'DEGRADED', RESOLVED: 'SUCCESS' };
  return <StatusBadge status={map[status]} label={t(`alertStatus.${status}`)} />;
}

export default function AlertsPage() {
  const i18n = useI18n();
  const { t, formatNumber } = i18n;
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const status = TABS.includes(params.get('status')) ? params.get('status') : 'ACTIVE';
  const severity = params.get('severity') || '';
  const serverId = params.get('serverId') || '';
  const page = Number(params.get('page') || 0);
  const set = (changes) => {
    const next = Object.fromEntries(Object.entries({ status, severity, serverId, page: 0, ...changes })
      .filter(([k, v]) => v && !(k === 'status' && v === 'ACTIVE') && !(k === 'page' && v === 0)));
    setParams(next, { replace: true });
  };
  const list = useApi(() => alertApi.list({ status, severity: severity || undefined, serverId: serverId || undefined, page, size: 25 }),
    [status, severity, serverId, page]);
  const counts = useApi(() => alertApi.counts(), []);
  const reloadAll = () => { list.reload(); counts.reload(); };
  useEffect(() => {
    const timer = setInterval(() => { list.reload(); counts.reload(); }, 60000);
    return () => clearInterval(timer);
  }, [list.reload, counts.reload]);
  const c = counts.data;
  const canOperate = can(PERMISSIONS.operateAlerts);

  return (
    <>
      <PageHeader title={t('alerts.title')} subtitle={t('alerts.subtitle')} breadcrumbs={[{ label: t('alerts.title') }]}
        actions={<Link to="/alerts/rules" className="btn btn-sm btn-outline-primary"><i className="bi bi-sliders me-1" aria-hidden="true" />{t('alerts.rules')}</Link>} />
      {c && (
        <div className="row g-3 mb-3">
          {[['alerts.open', c.open, 'bell-fill'], ['alerts.acknowledged', c.acknowledged, 'check2'],
            ['severity.CRITICAL', c.critical, 'radioactive'], ['severity.ERROR', c.error, 'x-octagon'],
            ['severity.WARNING', c.warning, 'exclamation-triangle']].map(([k, v, icon]) => (
            <div className="col-6 col-md" key={k}>
              <div className="card nm-stat h-100"><div className="card-body py-2">
                <div className="d-flex justify-content-between"><span className="nm-stat-label">{t(k)}</span><i className={`bi bi-${icon} text-body-secondary`} aria-hidden="true" /></div>
                <div className="nm-stat-value">{formatNumber(v)}</div>
              </div></div>
            </div>
          ))}
        </div>
      )}
      <div className="d-flex flex-wrap gap-2 align-items-center mb-3">
        <ul className="nav nav-pills nm-tabs" role="tablist">
          {TABS.map((s) => (
            <li className="nav-item" key={s} role="presentation">
              <button type="button" role="tab" aria-selected={status === s} className={`nav-link ${status === s ? 'active' : ''}`} onClick={() => set({ status: s })}>
                {t(`alerts.tab.${s}`)}
              </button>
            </li>
          ))}
        </ul>
        <select className="form-select form-select-sm w-auto" value={severity} aria-label={t('alerts.severity')} onChange={(e) => set({ severity: e.target.value })}>
          <option value="">{t('alerts.allSeverities')}</option>
          {SEVERITIES.map((s) => <option key={s} value={s}>{t(`severity.${s}`)}</option>)}
        </select>
        {serverId && <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => set({ serverId: '' })}>{t('alerts.allServers')}</button>}
      </div>
      <ErrorAlert error={list.error} onRetry={list.reload} />
      {!list.data && list.loading && <LoadingSpinner />}
      {list.data && list.data.totalElements === 0 && (
        <div className="card"><EmptyState icon="bell-slash" title={status === 'ACTIVE' ? t('alerts.noneActive') : t('alerts.none')}>
          {status === 'ACTIVE' && t('alerts.noneActiveHint')}</EmptyState></div>
      )}
      {list.data && list.data.totalElements > 0 && (
        <div className="card">
          <div className="table-responsive">
            <table className="table nm-table mb-0 align-middle">
              <thead><tr>
                <th>{t('alerts.severity')}</th><th>{t('alerts.alert')}</th><th>{t('alerts.condition')}</th>
                <th>{t('alerts.status')}</th><th>{t('alerts.first')}</th><th>{t('alerts.latest')}</th>{canOperate && <th />}
              </tr></thead>
              <tbody>
                {list.data.content.map((a) => (
                  <tr key={a.id}>
                    <td><SeverityBadge severity={a.severity} /></td>
                    <td>
                      <Link to={`/alerts/${a.id}`} className="fw-semibold">{a.ruleName}</Link>
                      <div className="small text-body-secondary">
                        {a.serverName || t('alerts.platform')}{a.resource && <> · <code className="nm-ltr" dir="ltr">{a.resource}</code></>}
                      </div>
                    </td>
                    <td className="small">{alertCondition(a, i18n)}</td>
                    <td>
                      {alertStatusBadge(a.status, t)}
                      {a.acknowledgedBy && a.status === 'ACKNOWLEDGED' && <div className="small text-body-secondary">{a.acknowledgedBy}</div>}
                    </td>
                    <td className="small"><TimeAgo value={a.firstTriggeredAt} /></td>
                    <td className="small">{a.resolvedAt ? <TimeAgo value={a.resolvedAt} /> : <TimeAgo value={a.lastTriggeredAt} />}</td>
                    {canOperate && <td><AlertActions alert={a} onChanged={reloadAll} /></td>}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="card-footer"><Pagination data={list.data} onPage={(p) => set({ page: p })} /></div>
        </div>
      )}
    </>
  );
}
