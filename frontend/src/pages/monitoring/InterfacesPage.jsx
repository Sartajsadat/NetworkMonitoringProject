import { useState } from 'react';
import { useI18n } from '../../i18n/I18nContext.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import { ifState } from '../server/NetworkTab.jsx';
import FleetShell, { FreshCell, ServerCell } from './FleetShell.jsx';
import { useFleet } from './useFleet.js';

export default function InterfacesPage() {
  const { t, units } = useI18n();
  const fleet = useFleet();
  const [hideIdle, setHideIdle] = useState(true);
  return (
    <FleetShell titleKey="interfaces.title" subtitleKey="interfaces.subtitle" fleet={fleet}
      actions={(
        <div className="form-check form-switch">
          <input className="form-check-input" type="checkbox" id="hide-idle" checked={hideIdle} onChange={(e) => setHideIdle(e.target.checked)} />
          <label className="form-check-label small" htmlFor="hide-idle">{t('interfaces.hideIdle')}</label>
        </div>
      )}>
      {(rows) => {
        const nics = rows.flatMap((e) => (e.network || []).map((n) => ({ e, n })))
          .filter(({ n }) => !hideIdle || (n.name !== 'lo' && n.operState === 'up'))
          .sort((a, b) => ((b.n.rxBitsPerSec ?? 0) + (b.n.txBitsPerSec ?? 0)) - ((a.n.rxBitsPerSec ?? 0) + (a.n.txBitsPerSec ?? 0)));
        return (
          <>
            <div className="alert alert-info small py-2"><i className="bi bi-info-circle me-1" aria-hidden="true" />{t('network.scopeNote')}</div>
            <div className="card table-responsive">
              <table className="table nm-table">
                <thead><tr><th>{t('fleet.col.server')}</th><th>{t('network.col.name')}</th><th>{t('network.col.state')}</th>
                  <th>{t('network.col.addresses')}</th><th className="text-end">{t('network.col.rx')}</th><th className="text-end">{t('network.col.tx')}</th>
                  <th className="text-end">{t('network.col.errors')}</th><th>{t('fleet.col.data')}</th></tr></thead>
                <tbody>
                  {nics.map(({ e, n }) => (
                    <tr key={`${e.serverId}-${n.name}`}>
                      <td><ServerCell entry={e} tab="network" /></td>
                      <td className="nm-mono"><span className="nm-ltr">{n.name}</span></td>
                      <td><StatusBadge status={ifState(n.operState)} label={n.operState || '?'} /></td>
                      <td className="small nm-mono"><span className="nm-ltr">{(n.addresses || []).join(', ') || '—'}</span></td>
                      <td className="text-end nm-num">{units.bits(n.rxBitsPerSec)}</td>
                      <td className="text-end nm-num">{units.bits(n.txBitsPerSec)}</td>
                      <td className={`text-end nm-num small ${n.errorsPerSec > 0 || n.dropsPerSec > 0 ? 'text-danger fw-semibold' : ''}`}>
                        {units.perSec(n.errorsPerSec)} / {units.perSec(n.dropsPerSec)}</td>
                      <td><FreshCell entry={e} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        );
      }}
    </FleetShell>
  );
}
