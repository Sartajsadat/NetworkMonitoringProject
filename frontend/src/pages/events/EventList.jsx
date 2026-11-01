import { Link } from 'react-router-dom';
import { useI18n } from '../../i18n/I18nContext.jsx';
import SeverityBadge from '../../components/SeverityBadge.jsx';
import { CATEGORY_ICON, eventSummary, eventTitle } from './eventText.js';

/** Compact chronological list (related events on detail pages). */
export default function EventList({ events, currentId }) {
  const i18n = useI18n();
  const { t, formatAxisTimeSeconds, formatDateTime } = i18n;
  if (!events || events.length === 0) return <div className="card-body small text-body-secondary">{t('event.noRelated')}</div>;
  return (
    <ol className="list-unstyled mb-0 nm-timeline">
      {events.map((e) => (
        <li key={e.id} className={`nm-timeline-item nm-sev-${e.severity.toLowerCase()} ${e.id === currentId ? 'nm-current' : ''}`}
          aria-current={e.id === currentId ? 'true' : undefined}>
          <div className="nm-timeline-time nm-num" title={formatDateTime(e.occurredAt)}>{formatAxisTimeSeconds(new Date(e.occurredAt).getTime())}</div>
          <div className="flex-grow-1 min-w-0">
            <div className="d-flex flex-wrap align-items-center gap-2">
              <SeverityBadge severity={e.severity} />
              <i className={`bi bi-${CATEGORY_ICON[e.category]}`} aria-label={t(`category.${e.category}`)} />
              {e.id === currentId ? <strong>{eventTitle(e, t)}</strong> : <Link to={`/events/${e.id}`}>{eventTitle(e, t)}</Link>}
              {e.resource && <code className="small nm-ltr" dir="ltr">{e.resource}</code>}
            </div>
            <div className="small">{eventSummary(e, i18n)}</div>
          </div>
        </li>
      ))}
    </ol>
  );
}
