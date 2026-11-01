import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';

export function ifState(op) {
  if (op === 'up') return 'IF_UP';
  if (op === 'down' || op === 'lowerlayerdown') return 'IF_DOWN';
  return 'IF_OTHER';
}

export default function NetworkTab({ latest }) {
  const { t, units, formatNumber } = useI18n();
  const nics = latest?.report?.network || [];
  if (nics.length === 0) return <div className="card"><EmptyState icon="ethernet" title={t('chart.noData')} /></div>;
  return (
    <div className="card">
      <div className="card-header small text-body-secondary">{t('network.scopeNote')}</div>
      <div className="table-responsive">
        <table className="table table-sm nm-table">
          <thead><tr>
            <th>{t('network.col.name')}</th><th>{t('network.col.state')}</th><th>{t('network.col.addresses')}</th>
            <th>{t('network.col.speed')}</th><th className="text-end">{t('network.col.rx')}</th><th className="text-end">{t('network.col.tx')}</th>
            <th className="text-end">{t('network.col.pps')}</th><th className="text-end">{t('network.col.errors')}</th>
            <th className="text-end">{t('network.col.totals')}</th>
          </tr></thead>
          <tbody>
            {nics.map((n) => (
              <tr key={n.name}>
                <td className="nm-mono"><span className="nm-ltr">{n.name}</span>{n.macAddress && <div className="small text-body-secondary nm-ltr">{n.macAddress}</div>}</td>
                <td><StatusBadge status={ifState(n.operState)} label={n.operState || '?'} /></td>
                <td className="small nm-mono"><span className="nm-ltr">{(n.addresses || []).join(', ') || '—'}</span></td>
                <td className="small">{n.speedMbps ? units.bits(n.speedMbps * 1e6).replace('/s', '') : '—'}</td>
                <td className="text-end nm-num">{units.bits(n.rxBitsPerSec)}</td>
                <td className="text-end nm-num">{units.bits(n.txBitsPerSec)}</td>
                <td className="text-end nm-num small">{units.perSec(n.rxPacketsPerSec)} / {units.perSec(n.txPacketsPerSec)}</td>
                <td className={`text-end nm-num small ${n.errorsPerSec > 0 || n.dropsPerSec > 0 ? 'text-danger fw-semibold' : ''}`}>
                  {units.perSec(n.errorsPerSec)} / {units.perSec(n.dropsPerSec)}
                </td>
                <td className="text-end small nm-num">{units.bytes(n.rxBytes)} / {units.bytes(n.txBytes)}
                  <div className="text-body-secondary">{t('network.errorsTotal', { n: formatNumber((n.rxErrors || 0) + (n.txErrors || 0)) })}</div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
