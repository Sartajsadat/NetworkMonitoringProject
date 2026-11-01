import { useI18n } from '../i18n/I18nContext.jsx';
import { levelFor } from '../utils/format.js';

/** Percentage with a small bar. Colour follows thresholds (75 % warning, 90 % critical) and the number is always shown. */
export default function UsageBar({ value, label }) {
  const { units } = useI18n();
  if (value === null || value === undefined) return <span className="text-body-secondary">—</span>;
  const level = levelFor(value);
  return (
    <div className="nm-usage">
      <div className="d-flex justify-content-between small">
        <span className="nm-num">{units.percent(value)}</span>
        {label && <span className="text-body-secondary nm-num">{label}</span>}
        {level === 'danger' && <i className="bi bi-exclamation-triangle-fill text-danger" aria-hidden="true" />}
      </div>
      <div className="progress" role="progressbar" aria-valuenow={Math.round(value)} aria-valuemin="0" aria-valuemax="100">
        <div className={`progress-bar bg-${level}`} style={{ width: `${Math.min(100, value)}%` }} />
      </div>
    </div>
  );
}
