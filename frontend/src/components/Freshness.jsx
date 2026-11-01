import { useI18n } from '../i18n/I18nContext.jsx';
import StatusBadge from './StatusBadge.jsx';

/** "Live" when data is recent, "Stale" with its age otherwise, so old values are never mistaken for current ones. */
export default function Freshness({ collectedAt, stale }) {
  const { t, formatRelative, formatDateTime } = useI18n();
  if (!collectedAt) return <StatusBadge status="NOT_COLLECTED" />;
  return (
    <span className="d-inline-flex align-items-center gap-2" title={formatDateTime(collectedAt)}>
      <StatusBadge status={stale ? 'STALE' : 'LIVE'} label={t(stale ? 'freshness.stale' : 'freshness.live')} />
      <small className="text-body-secondary">{t('freshness.collected')} {formatRelative(collectedAt)}</small>
    </span>
  );
}
