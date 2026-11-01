import { useI18n } from '../i18n/I18nContext.jsx';

/**
 * Status label with icon + text, so state is never conveyed by colour alone.
 * Unknown values fall back to a neutral badge showing the raw value.
 */
const STATUS = {
  ONLINE: { bg: 'success', icon: 'check-circle-fill' },
  OFFLINE: { bg: 'danger', icon: 'x-octagon-fill' },
  NO_AGENT: { bg: 'secondary', icon: 'dash-circle' },
  PENDING_AGENT: { bg: 'info', icon: 'hourglass-split' },
  ACTIVE: { bg: 'success', icon: 'activity' },
  PAUSED: { bg: 'warning', icon: 'pause-circle-fill' },
  DECOMMISSIONED: { bg: 'dark', icon: 'archive-fill' },
  HEALTHY: { bg: 'success', icon: 'heart-pulse-fill' },
  DEGRADED: { bg: 'warning', icon: 'exclamation-triangle-fill' },
  UNREACHABLE: { bg: 'danger', icon: 'wifi-off' },
  SUCCESS: { bg: 'success', icon: 'check-lg' },
  FAILURE: { bg: 'danger', icon: 'x-lg' },
  DENIED: { bg: 'warning', icon: 'shield-x' },
  AVAILABLE: { bg: 'success', icon: 'check-circle' },
  NOT_COLLECTED: { bg: 'secondary', icon: 'cloud-slash' },
  NOT_CONFIGURED: { bg: 'secondary', icon: 'gear' },
  UNSUPPORTED: { bg: 'dark', icon: 'slash-circle' },
  USER_ACTIVE: { bg: 'success', icon: 'person-check-fill' },
  USER_INACTIVE: { bg: 'secondary', icon: 'person-dash-fill' },
  USER_LOCKED: { bg: 'warning', icon: 'lock-fill' },
  // agents
  REVOKED: { bg: 'danger', icon: 'slash-circle-fill' },
  NOT_ENROLLED: { bg: 'secondary', icon: 'plug' },
  LIVE: { bg: 'success', icon: 'broadcast' },
  STALE: { bg: 'warning', icon: 'clock-history' },
  // services (systemd)
  RUNNING: { bg: 'success', icon: 'play-circle-fill' },
  STOPPED: { bg: 'secondary', icon: 'stop-circle' },
  FAILED: { bg: 'danger', icon: 'x-circle-fill' },
  STARTING: { bg: 'info', icon: 'arrow-up-circle' },
  STOPPING: { bg: 'info', icon: 'arrow-down-circle' },
  NOT_FOUND: { bg: 'dark', icon: 'question-diamond' },
  UNKNOWN: { bg: 'light', icon: 'question-circle' },
  // collectors
  OK: { bg: 'success', icon: 'check-lg' },
  UNAVAILABLE: { bg: 'secondary', icon: 'dash-circle' },
  PERMISSION_DENIED: { bg: 'warning', icon: 'lock-fill' },
  ERROR: { bg: 'danger', icon: 'exclamation-octagon-fill' },
  // interface operational state
  IF_UP: { bg: 'success', icon: 'arrow-up-circle-fill' },
  IF_DOWN: { bg: 'secondary', icon: 'arrow-down-circle' },
  IF_OTHER: { bg: 'light', icon: 'question-circle' },
};

export default function StatusBadge({ status, label }) {
  const { t } = useI18n();
  const s = STATUS[status] || { bg: 'light', icon: 'question-circle' };
  const text = label || (STATUS[status] ? t(`status.${status}`) : status);
  const textClass = ['warning', 'info', 'light'].includes(s.bg) ? 'text-dark' : '';
  return (
    <span className={`badge text-bg-${s.bg} ${textClass} d-inline-flex align-items-center gap-1`}>
      <i className={`bi bi-${s.icon}`} aria-hidden="true" />
      {text}
    </span>
  );
}
