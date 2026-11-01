import { useI18n } from '../../i18n/I18nContext.jsx';
import UsageBar from '../../components/UsageBar.jsx';
import FleetShell, { FreshCell, ServerCell } from './FleetShell.jsx';
import { useFleet } from './useFleet.js';

export default function CpuMemoryPage() {
  const { t, units, formatNumber, formatDuration } = useI18n();
  const fleet = useFleet();
  return (
    <FleetShell titleKey="cpuMem.title" subtitleKey="cpuMem.subtitle" fleet={fleet}>
      {(rows) => (
        <div className="card table-responsive">
          <table className="table nm-table">
            <thead><tr>
              <th>{t('fleet.col.server')}</th><th>{t('metric.cpu')}</th><th>{t('metric.iowait')}</th><th>{t('metric.load')}</th>
              <th>{t('cpuMem.cores')}</th><th>{t('metric.memory')}</th><th>{t('metric.swap')}</th><th>{t('serverDetail.uptime')}</th><th>{t('fleet.col.data')}</th>
            </tr></thead>
            <tbody>
              {[...rows].sort((a, b) => (b.cpu?.usagePercent ?? -1) - (a.cpu?.usagePercent ?? -1)).map((e) => (
                <tr key={e.serverId}>
                  <td><ServerCell entry={e} tab="metrics" /></td>
                  <td><UsageBar value={e.cpu?.usagePercent} /></td>
                  <td className="nm-num small">{units.percent(e.cpu?.iowaitPercent)}</td>
                  <td className="nm-num small">{e.cpu?.load1 != null
                    ? `${units.decimal(e.cpu.load1)} · ${units.decimal(e.cpu.load5)} · ${units.decimal(e.cpu.load15)}`
                    : e.cpu?.runQueue != null ? t('metric.queue', { n: units.decimal(e.cpu.runQueue, 0) }) : '—'}</td>
                  <td className="nm-num small">{e.host?.cpuCount ? formatNumber(e.host.cpuCount) : '—'}</td>
                  <td><UsageBar value={e.memory?.usedPercent} label={e.memory ? `${units.bytes(e.memory.usedBytes)} / ${units.bytes(e.memory.totalBytes)}` : null} /></td>
                  <td>{e.memory?.swapTotalBytes ? <UsageBar value={e.memory?.swapUsedPercent} /> : <span className="small text-body-secondary">{t('cpuMem.noSwap')}</span>}</td>
                  <td className="small">{formatDuration(e.host?.uptimeSeconds)}</td>
                  <td><FreshCell entry={e} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </FleetShell>
  );
}
