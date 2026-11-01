import { Bar, BarChart, CartesianGrid, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useI18n } from '../i18n/I18nContext.jsx';

/**
 * Single-series horizontal bar chart for small categorical counts. Mirrors for RTL: categories on
 * the right, bars grow leftwards. Values are labelled at the bar end; a hidden table repeats the data.
 */
/** Value label just past the bar's free end. Uses the rect's real edges, so it also works on a reversed (RTL) axis. */
function EndLabel({ x, y, width, height, value, isRtl, format }) {
  const left = Math.min(x, x + width);
  const right = Math.max(x, x + width);
  return (
    <text x={isRtl ? left - 6 : right + 6} y={y + height / 2} dy="0.35em" textAnchor={isRtl ? 'end' : 'start'}
      fill="var(--bs-body-color)" fontSize={12}>
      {format(value)}
    </text>
  );
}

export default function CountBarChart({ data, title }) {
  const { t, isRtl, formatNumber } = useI18n();
  if (!data || data.length === 0) return <div className="text-body-secondary small py-3">{t('common.noData')}</div>;
  const height = 40 + data.length * 36;
  const valueLabel = t('dashboard.chartValue');
  const tick = { fill: 'var(--nm-axis-text)', fontSize: 12 };
  return (
    <figure className="mb-0">
      <div style={{ width: '100%', height }} aria-hidden="true" dir="ltr">
        <ResponsiveContainer>
          <BarChart data={data} layout="vertical"
            margin={isRtl ? { top: 4, right: 8, bottom: 4, left: 36 } : { top: 4, right: 36, bottom: 4, left: 8 }}>
            <CartesianGrid horizontal={false} stroke="var(--nm-grid)" />
            <XAxis type="number" allowDecimals={false} reversed={isRtl} tick={tick} axisLine={false} tickLine={false}
              tickFormatter={formatNumber} />
            <YAxis type="category" dataKey="name" width={120} orientation={isRtl ? 'right' : 'left'} tick={tick}
              axisLine={false} tickLine={false} />
            <Tooltip cursor={{ fill: 'var(--nm-grid)', opacity: 0.4 }} formatter={(v) => [formatNumber(v), valueLabel]}
              contentStyle={{ background: 'var(--bs-body-bg)', border: '1px solid var(--bs-border-color)', color: 'var(--bs-body-color)' }} />
            <Bar dataKey="value" fill="var(--nm-series-1)" barSize={16} radius={isRtl ? [4, 0, 0, 4] : [0, 4, 4, 0]} isAnimationActive={false}>
              <LabelList dataKey="value" content={(props) => <EndLabel {...props} isRtl={isRtl} format={formatNumber} />} />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <table className="visually-hidden">
        <caption>{title}</caption>
        <thead><tr><th>{t('dashboard.chartCategory')}</th><th>{valueLabel}</th></tr></thead>
        <tbody>{data.map((d) => <tr key={d.name}><td>{d.name}</td><td>{formatNumber(d.value)}</td></tr>)}</tbody>
      </table>
    </figure>
  );
}
