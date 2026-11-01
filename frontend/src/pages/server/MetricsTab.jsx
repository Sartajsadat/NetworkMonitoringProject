import { useState } from 'react';
import { useI18n } from '../../i18n/I18nContext.jsx';
import MetricChart from '../../components/MetricChart.jsx';
import TimeRangePicker from '../../components/TimeRangePicker.jsx';

/** Historical charts. Interface/disk selectors list what the latest report contains. */
export default function MetricsTab({ serverId, latest }) {
  const { t } = useI18n();
  const [range, setRange] = useState({ preset: '1h' });
  const r = latest?.report;
  // Busiest interface first (total bytes), loopback last: that is the one people usually want to see.
  const nics = [...(r?.network || [])]
    .sort((a, b) => (a.name === 'lo') - (b.name === 'lo') || ((b.rxBytes || 0) + (b.txBytes || 0)) - ((a.rxBytes || 0) + (a.txBytes || 0)))
    .map((n) => n.name);
  const devices = (r?.diskIo || []).map((d) => d.device);
  const [nic, setNic] = useState(nics[0] || '');
  const [dev, setDev] = useState(devices[0] || '');
  const common = { serverId, range };

  return (
    <>
      <div className="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-3">
        <TimeRangePicker value={range} onChange={setRange} />
        <small className="text-body-secondary">{t('chart.resolutionHint')}</small>
      </div>
      <div className="row g-3">
        <div className="col-xl-6">
          <MetricChart {...common} title={t('chart.cpu')} unit="percent" yMax={100}
            lines={[{ metric: 'cpu.usage_pct', label: () => t('metric.cpuUsage') }, { metric: 'cpu.iowait_pct', label: () => t('metric.iowait') }]} />
        </div>
        <div className="col-xl-6">
          <MetricChart {...common} title={t('chart.memory')} unit="percent" yMax={100}
            lines={[{ metric: 'mem.used_pct', label: () => t('metric.memory') }, { metric: 'swap.used_pct', label: () => t('metric.swap') }]} />
        </div>
        <div className="col-xl-6">
          {r?.host?.platform === 'WINDOWS' ? (
            <div className="card h-100"><div className="card-body">
              <h3 className="h6">{t('chart.load')}</h3>
              <p className="small text-body-secondary mb-0">{t('chart.noLoadWindows')}</p>
            </div></div>
          ) : (
            <MetricChart {...common} title={t('chart.load')} unit="load"
              lines={[{ metric: 'load.1', label: () => t('metric.load1') }, { metric: 'load.5', label: () => t('metric.load5') }, { metric: 'load.15', label: () => t('metric.load15') }]} />
          )}
        </div>
        <div className="col-xl-6">
          <MetricChart {...common} title={t('chart.diskUsage')} unit="percent" yMax={100}
            lines={[{ metric: 'disk.used_pct', label: (s) => s.resource }]} />
        </div>
        <div className="col-xl-6">
          <div className="d-flex align-items-center gap-2 mb-2">
            <label htmlFor="nic-select" className="small text-body-secondary">{t('metric.interface')}</label>
            <select id="nic-select" className="form-select form-select-sm nm-ltr" style={{ width: 'auto' }} value={nic} onChange={(e) => setNic(e.target.value)}>
              {nics.map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </div>
          {nic && <MetricChart {...common} resource={nic} title={t('chart.network', { name: nic })} unit="bits_per_second"
            lines={[{ metric: 'net.rx_bps', label: () => t('metric.in') }, { metric: 'net.tx_bps', label: () => t('metric.out') }]} />}
        </div>
        <div className="col-xl-6">
          <div className="d-flex align-items-center gap-2 mb-2">
            <label htmlFor="dev-select" className="small text-body-secondary">{t('metric.device')}</label>
            <select id="dev-select" className="form-select form-select-sm nm-ltr" style={{ width: 'auto' }} value={dev} onChange={(e) => setDev(e.target.value)}>
              {devices.map((d) => <option key={d} value={d}>{d}</option>)}
            </select>
          </div>
          {dev && <MetricChart {...common} resource={dev} title={t('chart.diskIo', { name: dev })} unit="bytes_per_second"
            lines={[{ metric: 'diskio.read_Bps', label: () => t('metric.read') }, { metric: 'diskio.write_Bps', label: () => t('metric.write') }]} />}
        </div>
        <div className="col-xl-6">
          {nic && <MetricChart {...common} resource={nic} title={t('chart.errors', { name: nic })} unit="per_second"
            lines={[{ metric: 'net.errors_ps', label: () => t('metric.errors') }, { metric: 'net.drops_ps', label: () => t('metric.drops') }]} />}
        </div>
      </div>
    </>
  );
}
