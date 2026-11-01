import { useEffect, useMemo, useState } from 'react';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { metricsApi } from '../api/endpoints.js';
import { useI18n } from '../i18n/I18nContext.jsx';
import { UNIT_FORMAT } from '../utils/format.js';
import { resolveRange } from './TimeRangePicker.jsx';
import ErrorAlert from './ErrorAlert.jsx';

const COLORS = ['var(--nm-chart-1)', 'var(--nm-chart-2)', 'var(--nm-chart-3)', 'var(--nm-chart-4)'];

/**
 * Merges series into Recharts rows and inserts explicit null points where samples are missing
 * (gap > 2.5 x expected interval), so the line breaks instead of drawing across missing data.
 */
export function toRows(history, keyOf) {
  const rows = new Map();
  const step = (history?.expectedIntervalSeconds || 30) * 1000;
  for (const s of history?.series || []) {
    const key = keyOf(s);
    let prev = null;
    for (const p of s.points) {
      const t = new Date(p.t).getTime();
      if (prev !== null && t - prev > step * 2.5) {
        const gapT = prev + step;
        rows.set(gapT, { ...(rows.get(gapT) || { t: gapT }), [key]: null });
      }
      rows.set(t, { ...(rows.get(t) || { t }), [key]: p.v });
      prev = t;
    }
  }
  return [...rows.values()].sort((a, b) => a.t - b.t);
}

/**
 * Line chart for one metric group. `lines`: [{ metric, resource?, label }]. Fetches its own data
 * and refreshes every `refreshMs` while the range is live. Time flows left to right in every language.
 */
export default function MetricChart({ serverId, title, lines, range, unit, resource, yMax, refreshMs = 30000, height = 220 }) {
  const { t, units, formatAxisTime, formatDateTime } = useI18n();
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const metrics = [...new Set(lines.map((l) => l.metric))];
  const metricsKey = metrics.join(',');
  const rangeKey = JSON.stringify(range);

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      const r = resolveRange(range);
      try {
        const h = await metricsApi.history(serverId, { metrics, resource, from: r.from, to: r.to });
        if (!cancelled) { setData(h); setError(null); }
      } catch (e) {
        if (!cancelled) setError(e);
      }
    };
    load();
    const live = resolveRange(range).live;
    const timer = live ? setInterval(load, refreshMs) : null;
    return () => { cancelled = true; if (timer) clearInterval(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId, metricsKey, resource, rangeKey, refreshMs]);

  const keyOf = (s) => `${s.metric}|${s.resource}`;
  const rows = useMemo(() => toRows(data, keyOf), [data]);
  const fmt = units[UNIT_FORMAT[unit] || 'decimal'];
  // Colour follows the order of `lines` (first line = first colour), not the API's sort order.
  const order = (s) => {
    const i = lines.findIndex((l) => l.metric === s.metric && (l.resource === undefined || l.resource === s.resource));
    return i < 0 ? lines.length : i;
  };
  const shown = [...(data?.series || [])].sort((a, b) => order(a) - order(b) || a.resource.localeCompare(b.resource)).map((s) => {
    const line = lines[order(s)];
    return { key: keyOf(s), label: line?.label ? line.label(s) : `${s.metric} ${s.resource}` };
  }).slice(0, 4);
  const resolved = resolveRange(range);
  const spanMs = new Date(resolved.to) - new Date(resolved.from);
  const long = spanMs > 86400000;

  return (
    <div className="card h-100">
      <div className="card-body">
        <div className="d-flex justify-content-between align-items-start gap-2 mb-1">
          <h3 className="h6 mb-0">{title}</h3>
          {data && <small className="text-body-secondary">{data.resolution === 'RAW' ? t('chart.raw') : t('chart.rollup')}</small>}
        </div>
        <ErrorAlert error={error} />
        {!data && !error && <div className="text-body-secondary small py-5 text-center">{t('common.loading')}</div>}
        {data && rows.length === 0 && (
          <div className="text-body-secondary small py-5 text-center">{t('chart.noData')}</div>
        )}
        {data && rows.length > 0 && (
          <>
            {shown.length > 1 && (
              <div className="nm-chart-legend mb-1" aria-hidden="true">
                {shown.map((s, i) => <span key={s.key}><i style={{ background: COLORS[i] }} /><bdi>{s.label}</bdi></span>)}
              </div>
            )}
            <div style={{ width: '100%', height }} dir="ltr" role="img" aria-label={title}>
              <ResponsiveContainer>
                <LineChart data={rows} margin={{ top: 8, right: 12, bottom: 0, left: 4 }}>
                  <CartesianGrid stroke="var(--nm-grid)" vertical={false} />
                  <XAxis dataKey="t" type="number" scale="time" domain={[new Date(resolved.from).getTime(), new Date(resolved.to).getTime()]}
                    tickFormatter={(v) => formatAxisTime(v, long)} tick={{ fill: 'var(--nm-axis-text)', fontSize: 11 }}
                    axisLine={false} tickLine={false} minTickGap={40} />
                  <YAxis tickFormatter={(v) => fmt(v)} tick={{ fill: 'var(--nm-axis-text)', fontSize: 11 }} axisLine={false}
                    tickLine={false} width={72} domain={[0, yMax ?? 'auto']} allowDecimals />
                  <Tooltip
                    labelFormatter={(v) => formatDateTime(new Date(v).toISOString())}
                    formatter={(v, name) => [v === null ? t('chart.missing') : fmt(v), shown.find((s) => s.key === name)?.label || name]}
                    contentStyle={{ background: 'var(--bs-body-bg)', border: '1px solid var(--bs-border-color)', color: 'var(--bs-body-color)', fontSize: 12 }} />
                  {shown.map((s, i) => (
                    <Line key={s.key} dataKey={s.key} name={s.key} stroke={COLORS[i]} strokeWidth={2} dot={false}
                      isAnimationActive={false} connectNulls={false} activeDot={{ r: 4 }} />
                  ))}
                </LineChart>
              </ResponsiveContainer>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
