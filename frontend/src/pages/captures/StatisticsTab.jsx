import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { captureApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import CountBarChart from '../../components/CountBarChart.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import EmptyState from '../../components/EmptyState.jsx';

function CountTable({ title, rows, mono = true }) {
  const { t, formatNumber, units } = useI18n();
  return (
    <div className="card h-100">
      <div className="card-header"><h3 className="h6 mb-0">{title}</h3></div>
      {rows.length === 0 ? <div className="card-body small text-body-secondary">{t('common.noData')}</div> : (
        <div className="table-responsive">
          <table className="table table-sm nm-table mb-0">
            <thead><tr><th>{t('stats.col.value')}</th><th className="text-end">{t('stats.col.packets')}</th><th className="text-end">{t('stats.col.bytes')}</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.key}>
                  <td className={mono ? 'nm-mono small' : 'small'}><span className="nm-ltr">{r.key}</span></td>
                  <td className="text-end nm-num">{formatNumber(r.packets)}</td>
                  <td className="text-end nm-num text-nowrap">{units.bytes(r.bytes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default function StatisticsTab({ capture }) {
  const { t, formatNumber, units, formatAxisTime, formatAxisTimeSeconds, formatDuration } = useI18n();
  const { data, error, loading, reload } = useApi(() => captureApi.statistics(capture.id), [capture.id, capture.packetsIndexed]);
  if (!data && loading) return <LoadingSpinner />;
  if (error) return <ErrorAlert error={error} onRetry={reload} />;
  if (!data || data.packets === 0) return <div className="card"><EmptyState icon="bar-chart" title={t('analyzer.noPacketsYet')} /></div>;
  const span = data.first && data.last ? (new Date(data.last) - new Date(data.first)) / 1000 : null;
  const timeline = data.timeline.map((b) => ({ t: new Date(b.t).getTime(), packets: b.packets }));
  const long = span != null && span > 86400;
  const axisTime = (v) => (span != null && span < 3600 ? formatAxisTimeSeconds(v) : formatAxisTime(v, long));
  const tiles = [
    ['stats.packets', formatNumber(data.packets)],
    ['stats.bytes', units.bytes(data.bytes)],
    ['stats.span', span == null ? '—' : (span < 60 ? t('time.seconds', { s: formatNumber(Math.round(span)) }) : formatDuration(span))],
    ['stats.resets', formatNumber(data.tcpResets)],
    ['stats.retransmissions', formatNumber(data.retransmissions)],
    ['stats.syns', formatNumber(data.tcpSyns)],
  ];
  return (
    <>
      {data.indexTruncated && <div className="alert alert-info small">{t('analyzer.indexTruncated')}</div>}
      <div className="row g-3 mb-3">
        {tiles.map(([k, v]) => (
          <div className="col-6 col-md-4 col-xl-2" key={k}>
            <div className="card h-100 nm-stat"><div className="card-body py-2">
              <div className="small text-body-secondary">{t(k)}</div>
              <div className="fs-5 fw-semibold nm-num">{v}</div>
            </div></div>
          </div>
        ))}
      </div>

      <div className="row g-3 mb-3">
        <div className="col-lg-7">
          <div className="card h-100"><div className="card-body">
            <h3 className="h6">{t('stats.timeline')}</h3>
            <div style={{ width: '100%', height: 220 }} dir="ltr" role="img" aria-label={t('stats.timeline')}>
              <ResponsiveContainer>
                <AreaChart data={timeline} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke="var(--nm-grid)" vertical={false} />
                  <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']}
                    tickFormatter={axisTime} tick={{ fill: 'var(--nm-axis-text)', fontSize: 11 }} minTickGap={24} />
                  <YAxis allowDecimals={false} tickFormatter={(v) => formatNumber(v)} tick={{ fill: 'var(--nm-axis-text)', fontSize: 11 }}
                    axisLine={false} tickLine={false} width={48} />
                  <Tooltip labelFormatter={(v) => formatAxisTime(v, true)} formatter={(v) => [formatNumber(v), t('stats.packets')]} />
                  <Area type="stepAfter" dataKey="packets" stroke="var(--nm-chart-1)" fill="var(--nm-chart-1)" fillOpacity={0.15}
                    strokeWidth={1.5} isAnimationActive={false} />
                </AreaChart>
              </ResponsiveContainer>
            </div>
            {data.timeline[0] && (
              <div className="small text-body-secondary">{t('stats.bucket', { s: formatNumber(data.timeline[0].bucketSeconds) })}</div>
            )}
          </div></div>
        </div>
        <div className="col-lg-5">
          <div className="card h-100"><div className="card-body">
            <h3 className="h6">{t('stats.protocols')}</h3>
            <CountBarChart title={t('stats.protocols')} data={data.protocols.slice(0, 8).map((p) => ({ name: p.key, value: p.packets }))} />
          </div></div>
        </div>
      </div>

      <div className="card mb-3">
        <div className="card-header"><h3 className="h6 mb-0">{t('stats.conversations')}</h3></div>
        {data.conversations.length === 0 ? <div className="card-body small text-body-secondary">{t('common.noData')}</div> : (
          <div className="table-responsive">
            <table className="table table-sm nm-table mb-0">
              <thead><tr>
                <th>{t('stats.col.addressA')}</th><th>{t('stats.col.addressB')}</th><th>{t('stats.col.protocols')}</th>
                <th className="text-end">{t('stats.col.packets')}</th><th className="text-end">{t('stats.col.aToB')}</th>
                <th className="text-end">{t('stats.col.bToA')}</th><th className="text-end">{t('stats.col.bytes')}</th>
              </tr></thead>
              <tbody>
                {data.conversations.map((c) => (
                  <tr key={`${c.a}|${c.b}`}>
                    <td className="nm-mono small"><span className="nm-ltr">{c.a}</span></td>
                    <td className="nm-mono small"><span className="nm-ltr">{c.b}</span></td>
                    <td className="small nm-ltr">{c.protocols}</td>
                    <td className="text-end nm-num">{formatNumber(c.packets)}</td>
                    <td className="text-end nm-num">{formatNumber(c.packetsAtoB)}</td>
                    <td className="text-end nm-num">{formatNumber(c.packetsBtoA)}</td>
                    <td className="text-end nm-num">{units.bytes(c.bytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="row g-3">
        <div className="col-lg-6 col-xl-3"><CountTable title={t('stats.topSources')} rows={data.topSources} /></div>
        <div className="col-lg-6 col-xl-3"><CountTable title={t('stats.topDestinations')} rows={data.topDestinations} /></div>
        <div className="col-lg-6 col-xl-3"><CountTable title={t('stats.topPorts')} rows={data.topPorts} /></div>
        <div className="col-lg-6 col-xl-3"><CountTable title={t('stats.dnsQueries')} rows={data.dnsQueries} /></div>
      </div>
    </>
  );
}
