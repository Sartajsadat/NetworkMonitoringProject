import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useI18n } from '../../i18n/I18nContext.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import FleetShell, { FreshCell, ServerCell } from './FleetShell.jsx';
import { useFleet } from './useFleet.js';

const ORDER = { FAILED: 0, NOT_FOUND: 1, STOPPED: 2, STOPPING: 3, STARTING: 4, UNKNOWN: 5, RUNNING: 6 };

export default function ServicesPage() {
  const { t } = useI18n();
  const fleet = useFleet();
  const [problemsOnly, setProblemsOnly] = useState(false);
  return (
    <FleetShell titleKey="servicesPage.title" subtitleKey="servicesPage.subtitle" fleet={fleet}
      actions={(
        <div className="form-check form-switch">
          <input className="form-check-input" type="checkbox" id="problems" checked={problemsOnly} onChange={(e) => setProblemsOnly(e.target.checked)} />
          <label className="form-check-label small" htmlFor="problems">{t('servicesPage.problemsOnly')}</label>
        </div>
      )}>
      {(rows) => {
        const services = rows.flatMap((e) => (e.services || []).map((s) => ({ e, s })))
          .filter(({ s }) => !problemsOnly || s.status !== 'RUNNING')
          .sort((a, b) => (ORDER[a.s.status] ?? 9) - (ORDER[b.s.status] ?? 9) || a.e.name.localeCompare(b.e.name));
        return (
          <>
            <div className="card table-responsive mb-3">
              <table className="table nm-table">
                <thead><tr><th>{t('fleet.col.server')}</th><th>{t('services.col.unit')}</th><th>{t('services.col.status')}</th>
                  <th>{t('services.col.state')}</th><th>{t('services.col.since')}</th><th>{t('fleet.col.data')}</th></tr></thead>
                <tbody>
                  {services.length === 0 && (
                    <tr><td colSpan={6} className="text-center text-body-secondary small py-4">{t('servicesPage.none')}</td></tr>
                  )}
                  {services.map(({ e, s }) => (
                    <tr key={`${e.serverId}-${s.name}`}>
                      <td><ServerCell entry={e} tab="processes" /></td>
                      <td className="nm-mono"><span className="nm-ltr">{s.name}</span></td>
                      <td><StatusBadge status={s.status} label={t(`svc.${s.status}`)} /></td>
                      <td className="small nm-ltr">{[s.activeState, s.subState].filter(Boolean).join(' / ')}</td>
                      <td className="small"><TimeAgo value={s.since} never="—" /></td>
                      <td><FreshCell entry={e} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="small text-body-secondary">
              {t('servicesPage.processesHint')}{' '}
              {rows.slice(0, 5).map((e, i) => (
                <span key={e.serverId}>{i > 0 && ' · '}<Link to={`/servers/${e.serverId}?tab=processes`}>{e.name}</Link></span>
              ))}
            </p>
          </>
        );
      }}
    </FleetShell>
  );
}
