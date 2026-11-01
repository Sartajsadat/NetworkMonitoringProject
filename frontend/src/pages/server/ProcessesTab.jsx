import { Link } from 'react-router-dom';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';

export default function ProcessesTab({ latest, canManage }) {
  const { t, units, formatNumber } = useI18n();
  const services = latest?.report?.services || [];
  const procs = latest?.report?.processes || [];
  return (
    <div className="row g-3">
      <div className="col-12">
        <div className="card">
          <div className="card-header d-flex justify-content-between align-items-center gap-2 flex-wrap">
            <h2 className="h6 mb-0">{t('services.title')}</h2>
            <small className="text-body-secondary">{t('services.readOnly')}</small>
          </div>
          {services.length === 0 ? (
            <div className="card-body small text-body-secondary">
              {t('services.none')} {canManage && <Link to="?tab=agent">{t('services.configure')}</Link>}
            </div>
          ) : (
            <div className="table-responsive">
              <table className="table table-sm nm-table">
                <thead><tr><th>{t('services.col.unit')}</th><th>{t('services.col.status')}</th><th>{t('services.col.state')}</th><th>{t('services.col.since')}</th></tr></thead>
                <tbody>
                  {services.map((s) => (
                    <tr key={s.name}>
                      <td className="nm-mono"><span className="nm-ltr">{s.name}</span></td>
                      <td><StatusBadge status={s.status} label={t(`svc.${s.status}`)} /></td>
                      <td className="small nm-ltr">{[s.activeState, s.subState].filter(Boolean).join(' / ')}</td>
                      <td className="small"><TimeAgo value={s.since} never="—" /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
      <div className="col-12">
        <div className="card">
          <div className="card-header d-flex justify-content-between align-items-center gap-2 flex-wrap">
            <h2 className="h6 mb-0">{t('processes.title')}</h2>
            <small className="text-body-secondary">{t('processes.privacy')}</small>
          </div>
          {procs.length === 0 ? <EmptyState icon="list-task" title={t('chart.noData')} /> : (
            <div className="table-responsive">
              <table className="table table-sm nm-table">
                <thead><tr><th>{t('processes.col.name')}</th><th>{t('processes.col.pid')}</th><th>{t('processes.col.user')}</th>
                  <th className="text-end">{t('processes.col.cpu')}</th><th className="text-end">{t('processes.col.memory')}</th>
                  <th>{t('processes.col.state')}</th><th>{t('processes.col.started')}</th></tr></thead>
                <tbody>
                  {procs.map((p) => (
                    <tr key={p.pid}>
                      <td className="nm-mono"><span className="nm-ltr">{p.name}</span></td>
                      <td className="nm-num small">{formatNumber(p.pid)}</td>
                      <td className="small nm-ltr">{p.user || '—'}</td>
                      <td className="text-end nm-num">{units.percent(p.cpuPercent)}</td>
                      <td className="text-end nm-num">{units.bytes(p.memoryBytes)} <small className="text-body-secondary">{p.memoryPercent != null && `(${units.percent(p.memoryPercent)})`}</small></td>
                      <td className="small nm-ltr">{p.state}</td>
                      <td className="small"><TimeAgo value={p.startedAt} never="—" /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="card-footer small text-body-secondary">{t('processes.cpuNote')}</div>
        </div>
      </div>
    </div>
  );
}
