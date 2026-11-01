import { useI18n } from '../i18n/I18nContext.jsx';

/** Date/time in the reader's language and calendar, with a relative hint and the UTC ISO value on hover. */
export default function TimeAgo({ value, never }) {
  const { t, formatDateTime, formatRelative } = useI18n();
  if (!value) return <span className="text-body-secondary">{never || t('common.never')}</span>;
  return (
    <time dateTime={value} title={new Date(value).toISOString()}>
      {formatDateTime(value)} <small className="text-body-secondary">({formatRelative(value)})</small>
    </time>
  );
}
