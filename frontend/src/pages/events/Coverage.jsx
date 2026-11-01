import { useI18n } from '../../i18n/I18nContext.jsx';

/**
 * Says plainly how much metric data exists for a window and where it is missing, so a chart
 * with gaps is never mistaken for "nothing happened".
 */
export default function Coverage({ coverage }) {
  const { t, formatNumber, formatDateTime, formatDuration } = useI18n();
  if (!coverage) return null;
  const none = coverage.samples === 0;
  const full = !none && coverage.gaps.length === 0;
  const cls = none ? 'alert-danger' : full ? 'alert-success' : 'alert-warning';
  return (
    <div className={`alert ${cls} small mb-0`} role="status">
      <div className="fw-semibold">
        <i className={`bi ${none ? 'bi-x-octagon' : full ? 'bi-check-circle' : 'bi-exclamation-triangle'} me-1`} aria-hidden="true" />
        {none ? t('coverage.none') : full ? t('coverage.full') : t('coverage.partial', { p: formatNumber(coverage.percent) })}
      </div>
      <div>
        {t('coverage.counts', { n: formatNumber(coverage.samples), e: formatNumber(coverage.expectedSamples),
          i: formatNumber(coverage.expectedIntervalSeconds) })}
      </div>
      {coverage.gaps.length > 0 && (
        <ul className="mb-0 mt-1 ps-3">
          {coverage.gaps.slice(0, 8).map((g) => (
            <li key={g.from}>
              {t('coverage.gap', { from: formatDateTime(g.from), to: formatDateTime(g.to),
                d: formatDuration((new Date(g.to) - new Date(g.from)) / 1000) })}
            </li>
          ))}
        </ul>
      )}
      {(none || coverage.gaps.length > 0) && <div className="mt-1 text-body-secondary">{t('coverage.explain')}</div>}
    </div>
  );
}
