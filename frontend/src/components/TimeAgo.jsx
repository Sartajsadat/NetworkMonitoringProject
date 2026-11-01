/** Absolute UTC timestamp with a relative hint. Shows "Never" for null. */
export function formatDateTime(iso) {
  if (!iso) return null;
  const d = new Date(iso);
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
}

export function relative(iso, now = Date.now()) {
  const diff = Math.round((now - new Date(iso).getTime()) / 1000);
  if (diff < 60) return `${diff}s ago`;
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  return `${Math.floor(diff / 86400)}d ago`;
}

export default function TimeAgo({ value, never = 'Never' }) {
  if (!value) return <span className="text-body-secondary">{never}</span>;
  return (
    <time dateTime={value} title={new Date(value).toISOString()}>
      {formatDateTime(value)} <small className="text-body-secondary">({relative(value)})</small>
    </time>
  );
}
