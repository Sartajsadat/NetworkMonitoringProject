import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { networkApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Pagination from '../../components/Pagination.jsx';
import TimeRangePicker, { resolveRange } from '../../components/TimeRangePicker.jsx';
import { protocolName } from './networkText.js';

function TopTable({ title, rows, render, filter }) {
  const { t, units, formatNumber } = useI18n();
  return (
    <div className="card h-100">
      <div className="card-header"><h2 className="h6 mb-0">{title}</h2></div>
      {rows.length === 0 ? <div className="card-body small text-body-secondary">{t('common.noData')}</div> : (
        <div className="table-responsive">
          <table className="table table-sm nm-table mb-0">
            <thead><tr><th>{t('traffic.value')}</th><th className="text-end">{t('traffic.bytes')}</th>
              <th className="text-end">{t('traffic.packets')}</th><th className="text-end">{t('traffic.flows')}</th></tr></thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i}>
                  <td className="small">{filter ? <button type="button" className="btn btn-link btn-sm p-0" onClick={() => filter(r)}>{render(r)}</button> : render(r)}</td>
                  <td className="text-end nm-num text-nowrap">{units.bytes(r.bytes)}</td>
                  <td className="text-end nm-num">{formatNumber(r.packets)}</td>
                  <td className="text-end nm-num">{formatNumber(r.flows)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default function TrafficPage() {
  const { t, units, formatNumber, formatDateTime, formatAxisTime, formatAxisTimeSeconds, formatDuration } = useI18n();
  const [params, setParams] = useSearchParams();
  const range = params.get('from') && params.get('to') ? { from: params.get('from'), to: params.get('to') } : { preset: params.get('range') || '1h' };
  const f = { ip: params.get('ip') || '', subnet: params.get('subnet') || '', port: params.get('port') || '',
    protocol: params.get('protocol') || '', exporterId: params.get('exporterId') || '', page: Number(params.get('page') || 0) };
  const [draft, setDraft] = useState(f);
  useEffect(() => setDraft(f), [params.toString()]); // eslint-disable-line react-hooks/exhaustive-deps
  const update = (c) => {
    const next = { ...f, page: 0, ...c };
    const r = c.range || range;
    const out = r.preset ? { range: r.preset } : { from: r.from, to: r.to };
    ['ip', 'subnet', 'port', 'protocol', 'exporterId'].forEach((k) => { if (next[k]) out[k] = next[k]; });
    if (next.page) out.page = String(next.page);
    setParams(out, { replace: true });
  };
  const resolved = resolveRange(range);
  const q = { from: resolved.from, to: resolved.to, ip: f.ip || undefined, subnet: f.subnet || undefined, port: f.port || undefined,
    protocol: f.protocol || undefined, exporterId: f.exporterId || undefined };
  const key = JSON.stringify({ ...q, from: range.preset || q.from, to: range.preset ? '' : q.to });
  const summary = useApi(() => networkApi.flowSummary(q), [key]);
  const records = useApi(() => networkApi.flows({ ...q, page: f.page, size: 25 }), [key, f.page]);
  const collector = useApi(() => networkApi.collector(), []);
  const devices = useApi(() => networkApi.devices(), []);
  useEffect(() => {
    if (!resolved.live) return undefined;
    const timer = setInterval(() => { summary.reload(); records.reload(); }, 30000);
    return () => clearInterval(timer);
  }, [resolved.live, summary.reload, records.reload]);

  const s = summary.data;
  const chart = (s?.timeline || []).map((b) => ({ t: new Date(b.t).getTime(), bytes: b.bytes }));
  const axis = (v) => ((s?.bucketSeconds || 60) < 3600 ? formatAxisTimeSeconds(v) : formatAxisTime(v, true));
  const ipLink = (a) => <span className="nm-mono" dir="ltr">{a}</span>;
  const exporters = (devices.data || []).filter((d) => d.flowExport);

  return (
    <>
      <PageHeader title={t('traffic.title')} subtitle={t('traffic.subtitle')} breadcrumbs={[{ label: t('traffic.title') }]}
        actions={<TimeRangePicker value={range} onChange={(r) => update({ range: r })} />} />
      <div className="alert alert-secondary small d-flex gap-2">
        <i className="bi bi-info-circle mt-1" aria-hidden="true" />
        <div>
          <strong>{t('traffic.flowLabel')}</strong> {t('traffic.flowExplain')}{' '}
          {s?.sampled && <span className="text-danger">{t('traffic.sampledNote')}</span>}
        </div>
      </div>
      {collector.data && !collector.data.listening && (
        <div className="alert alert-warning small">{t('traffic.collectorOff')} <Link to="/network-devices">{t('nav.networkDevices')}</Link></div>
      )}
      <div className="card mb-3"><div className="card-body">
        <form className="row g-2 align-items-end" onSubmit={(e) => { e.preventDefault(); update(draft); }}>
          <div className="col-md-3"><label className="form-label small" htmlFor="tr-ip">{t('timeline.ip')}</label>
            <input id="tr-ip" className="form-control form-control-sm nm-ltr" dir="ltr" maxLength={45} value={draft.ip} onChange={(e) => setDraft({ ...draft, ip: e.target.value })} /></div>
          <div className="col-md-3"><label className="form-label small" htmlFor="tr-net">{t('ips.subnet')}</label>
            <input id="tr-net" className="form-control form-control-sm nm-ltr" dir="ltr" maxLength={50} placeholder="10.10.1.0/24" value={draft.subnet} onChange={(e) => setDraft({ ...draft, subnet: e.target.value })} /></div>
          <div className="col-6 col-md-1"><label className="form-label small" htmlFor="tr-port">{t('analyzer.port')}</label>
            <input id="tr-port" type="number" min={0} max={65535} className="form-control form-control-sm" value={draft.port} onChange={(e) => setDraft({ ...draft, port: e.target.value })} /></div>
          <div className="col-6 col-md-2"><label className="form-label small" htmlFor="tr-proto">{t('analyzer.protocol')}</label>
            <select id="tr-proto" className="form-select form-select-sm" value={draft.protocol} onChange={(e) => setDraft({ ...draft, protocol: e.target.value })}>
              <option value="">{t('common.all')}</option>{[6, 17, 1, 58, 47, 50].map((p) => <option key={p} value={p}>{protocolName(p)}</option>)}</select></div>
          <div className="col-md-2"><label className="form-label small" htmlFor="tr-exp">{t('traffic.exporter')}</label>
            <select id="tr-exp" className="form-select form-select-sm" value={draft.exporterId} onChange={(e) => setDraft({ ...draft, exporterId: e.target.value })}>
              <option value="">{t('common.all')}</option>{exporters.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}</select></div>
          <div className="col-md-1 d-flex gap-1"><button type="submit" className="btn btn-sm btn-outline-primary w-100">{t('common.apply')}</button></div>
        </form>
      </div></div>
      <ErrorAlert error={summary.error} onRetry={summary.reload} />
      {!s && summary.loading && <LoadingSpinner />}
      {s && s.flows === 0 && <div className="card"><EmptyState icon="arrow-left-right" title={t('traffic.none')}>{t('traffic.noneHint')}</EmptyState></div>}
      {s && s.flows > 0 && (
        <>
          <div className="row g-3 mb-3">
            {[['traffic.bytes', units.bytes(s.bytes)], ['traffic.packets', formatNumber(s.packets)], ['traffic.flows', formatNumber(s.flows)],
              ['traffic.exporters', formatNumber(s.exporters.length)]].map(([k, v]) => (
              <div className="col-6 col-md-3" key={k}><div className="card nm-stat h-100"><div className="card-body py-2">
                <div className="nm-stat-label">{t(k)}</div><div className="nm-stat-value">{v}</div></div></div></div>
            ))}
          </div>
          <div className="card mb-3"><div className="card-body">
            <h2 className="h6">{t('traffic.overTime')}</h2>
            <div style={{ width: '100%', height: 200 }} dir="ltr" role="img" aria-label={t('traffic.overTime')}>
              <ResponsiveContainer>
                <AreaChart data={chart} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke="var(--nm-grid)" vertical={false} />
                  <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']} tickFormatter={axis} minTickGap={24}
                    tick={{ fill: 'var(--nm-axis-text)', fontSize: 11 }} />
                  <YAxis tickFormatter={(v) => units.bytes(v)} width={70} tick={{ fill: 'var(--nm-axis-text)', fontSize: 11 }} axisLine={false} tickLine={false} />
                  <Tooltip labelFormatter={(v) => formatDateTime(new Date(v).toISOString())} formatter={(v) => [units.bytes(v), t('traffic.bytes')]} />
                  <Area type="stepAfter" dataKey="bytes" stroke="var(--nm-chart-1)" fill="var(--nm-chart-1)" fillOpacity={0.15} isAnimationActive={false} />
                </AreaChart>
              </ResponsiveContainer>
            </div>
            <div className="small text-body-secondary">{t('traffic.bucket', { d: formatDuration(s.bucketSeconds) })}</div>
          </div></div>
          <div className="row g-3 mb-3">
            <div className="col-xl-6"><TopTable title={t('traffic.topSources')} rows={s.topSources} render={(r) => ipLink(r.address)} filter={(r) => update({ ip: r.address })} /></div>
            <div className="col-xl-6"><TopTable title={t('traffic.topDestinations')} rows={s.topDestinations} render={(r) => ipLink(r.address)} filter={(r) => update({ ip: r.address })} /></div>
            <div className="col-xl-6"><TopTable title={t('traffic.topPorts')} rows={s.topPorts}
              render={(r) => <span dir="ltr">{protocolName(r.protocol)}{r.port != null && ` ${r.port}`}</span>}
              filter={(r) => update({ port: r.port != null ? String(r.port) : '', protocol: String(r.protocol) })} /></div>
            <div className="col-xl-6"><TopTable title={t('traffic.protocols')} rows={s.protocols} render={(r) => protocolName(r.protocol)}
              filter={(r) => update({ protocol: String(r.protocol) })} /></div>
          </div>
          <div className="card mb-3">
            <div className="card-header"><h2 className="h6 mb-0">{t('traffic.conversations')}</h2></div>
            <div className="table-responsive">
              <table className="table table-sm nm-table mb-0">
                <thead><tr><th>{t('stats.col.addressA')}</th><th>{t('stats.col.addressB')}</th><th className="text-end">{t('traffic.bytes')}</th>
                  <th className="text-end">{t('stats.col.aToB')}</th><th className="text-end">{t('stats.col.bToA')}</th><th className="text-end">{t('traffic.flows')}</th></tr></thead>
                <tbody>{s.conversations.map((c) => (
                  <tr key={`${c.a}|${c.b}`}><td>{ipLink(c.a)}</td><td>{ipLink(c.b)}</td><td className="text-end nm-num text-nowrap">{units.bytes(c.bytes)}</td>
                    <td className="text-end nm-num text-nowrap">{units.bytes(c.bytesAtoB)}</td><td className="text-end nm-num text-nowrap">{units.bytes(c.bytesBtoA)}</td>
                    <td className="text-end nm-num">{formatNumber(c.flows)}</td></tr>))}</tbody>
              </table>
            </div>
          </div>
          {records.data && (
            <div className="card">
              <div className="card-header"><h2 className="h6 mb-0">{t('traffic.records')}</h2></div>
              <div className="table-responsive">
                <table className="table table-sm nm-table mb-0" dir="ltr">
                  <thead><tr><th>{t('traffic.end')}</th><th>{t('analyzer.col.source')}</th><th>{t('analyzer.col.destination')}</th>
                    <th>{t('analyzer.col.protocol')}</th><th className="text-end">{t('traffic.bytes')}</th><th className="text-end">{t('traffic.packets')}</th>
                    <th>{t('traffic.exporter')}</th></tr></thead>
                  <tbody>{records.data.content.map((r) => (
                    <tr key={r.id}>
                      <td className="small nm-mono text-nowrap">{formatAxisTimeSeconds(new Date(r.end).getTime())}</td>
                      <td className="small nm-mono">{r.src}{r.srcPort != null && `:${r.srcPort}`}</td>
                      <td className="small nm-mono">{r.dst}{r.dstPort != null && `:${r.dstPort}`}</td>
                      <td className="small">{protocolName(r.protocol)}</td>
                      <td className="text-end nm-num small">{units.bytes(r.bytes)}</td>
                      <td className="text-end nm-num small">{formatNumber(r.packets)}</td>
                      <td className="small">{r.exporter} <span className="text-body-secondary">{r.version === 10 ? 'IPFIX' : `v${r.version}`}{r.sampling > 1 && ` 1:${r.sampling}`}</span></td>
                    </tr>))}</tbody>
                </table>
              </div>
              <div className="card-footer"><Pagination data={records.data} onPage={(page) => update({ page })} /></div>
            </div>
          )}
        </>
      )}
    </>
  );
}
