import { Bar, BarChart, CartesianGrid, LabelList, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

/**
 * Single-series horizontal bar chart for small categorical counts.
 * One colour (identity is in the axis labels), values labelled at the bar end,
 * hover tooltip, thin bars with rounded data ends, recessive grid.
 * A screen-reader table carries the same numbers.
 */
export default function CountBarChart({ data, title, valueLabel = 'Servers' }) {
  if (!data || data.length === 0) return <div className="text-body-secondary small py-3">No data.</div>;
  const height = 40 + data.length * 34;
  return (
    <figure className="mb-0">
      <div style={{ width: '100%', height }} aria-hidden="true">
        <ResponsiveContainer>
          <BarChart data={data} layout="vertical" margin={{ top: 4, right: 36, bottom: 4, left: 8 }}>
            <CartesianGrid horizontal={false} stroke="var(--nm-grid)" strokeDasharray="0" />
            <XAxis type="number" allowDecimals={false} tick={{ fill: 'var(--nm-axis-text)', fontSize: 12 }}
              axisLine={false} tickLine={false} />
            <YAxis type="category" dataKey="name" width={110} tick={{ fill: 'var(--nm-axis-text)', fontSize: 12 }}
              axisLine={false} tickLine={false} />
            <Tooltip cursor={{ fill: 'var(--nm-grid)', opacity: 0.4 }} formatter={(v) => [v, valueLabel]}
              contentStyle={{ background: 'var(--bs-body-bg)', border: '1px solid var(--bs-border-color)', color: 'var(--bs-body-color)' }} />
            <Bar dataKey="value" fill="var(--nm-series-1)" barSize={16} radius={[0, 4, 4, 0]} isAnimationActive={false}>
              <LabelList dataKey="value" position="right" fill="var(--bs-body-color)" fontSize={12} />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>
      <table className="visually-hidden">
        <caption>{title}</caption>
        <thead><tr><th>Category</th><th>{valueLabel}</th></tr></thead>
        <tbody>{data.map((d) => <tr key={d.name}><td>{d.name}</td><td>{d.value}</td></tr>)}</tbody>
      </table>
    </figure>
  );
}
