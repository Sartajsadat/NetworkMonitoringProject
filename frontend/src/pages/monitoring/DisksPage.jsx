import { useState } from 'react';
import { useI18n } from '../../i18n/I18nContext.jsx';
import UsageBar from '../../components/UsageBar.jsx';
import FleetShell, { FreshCell, ServerCell } from './FleetShell.jsx';
import { useFleet } from './useFleet.js';

export default function DisksPage() {
  const { t, units } = useI18n();
  const fleet = useFleet();
  const [onlyFull, setOnlyFull] = useState(false);
  return (
    <FleetShell titleKey="disks.title" subtitleKey="disks.subtitle" fleet={fleet}
      actions={(
        <div className="form-check form-switch">
          <input className="form-check-input" type="checkbox" id="only-full" checked={onlyFull} onChange={(e) => setOnlyFull(e.target.checked)} />
          <label className="form-check-label small" htmlFor="only-full">{t('disks.onlyFull')}</label>
        </div>
      )}>
      {(rows) => {
        const disks = rows.flatMap((e) => (e.disks || []).map((d) => ({ e, d })))
          .filter(({ d }) => !onlyFull || (d.usedPercent ?? 0) >= 80)
          .sort((a, b) => (b.d.usedPercent ?? 0) - (a.d.usedPercent ?? 0));
        const io = rows.flatMap((e) => (e.diskIo || []).map((d) => ({ e, d })))
          .sort((a, b) => ((b.d.readBytesPerSec ?? 0) + (b.d.writeBytesPerSec ?? 0)) - ((a.d.readBytesPerSec ?? 0) + (a.d.writeBytesPerSec ?? 0)));
        return (
          <>
            <div className="card table-responsive mb-3">
              <table className="table nm-table">
                <thead><tr><th>{t('fleet.col.server')}</th><th>{t('storage.col.mount')}</th><th>{t('storage.col.type')}</th>
                  <th>{t('storage.col.used')}</th><th className="text-end">{t('storage.col.free')}</th><th className="text-end">{t('storage.col.total')}</th><th>{t('fleet.col.data')}</th></tr></thead>
                <tbody>
                  {disks.length === 0 && <tr><td colSpan={7} className="text-center text-body-secondary small py-4">{t('disks.noneFull')}</td></tr>}
                  {disks.map(({ e, d }) => (
                    <tr key={`${e.serverId}-${d.mount}`}>
                      <td><ServerCell entry={e} tab="storage" /></td>
                      <td className="nm-mono"><span className="nm-ltr">{d.mount}</span><div className="small text-body-secondary nm-ltr">{d.device}</div></td>
                      <td className="small nm-ltr">{d.fsType}</td>
                      <td><UsageBar value={d.usedPercent} /></td>
                      <td className="text-end nm-num">{units.bytes(d.freeBytes)}</td>
                      <td className="text-end nm-num">{units.bytes(d.totalBytes)}</td>
                      <td><FreshCell entry={e} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="card table-responsive">
              <div className="card-header"><h2 className="h6 mb-0">{t('storage.io')}</h2></div>
              <table className="table table-sm nm-table">
                <thead><tr><th>{t('fleet.col.server')}</th><th>{t('storage.col.device')}</th><th className="text-end">{t('metric.read')}</th>
                  <th className="text-end">{t('metric.write')}</th><th>{t('storage.col.busy')}</th></tr></thead>
                <tbody>
                  {io.map(({ e, d }) => (
                    <tr key={`${e.serverId}-${d.device}`}>
                      <td><ServerCell entry={e} tab="storage" /></td>
                      <td className="nm-mono"><span className="nm-ltr">{d.device}</span></td>
                      <td className="text-end nm-num">{units.bytesPerSec(d.readBytesPerSec)}</td>
                      <td className="text-end nm-num">{units.bytesPerSec(d.writeBytesPerSec)}</td>
                      <td><UsageBar value={d.busyPercent} /></td>
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
