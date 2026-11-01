/**
 * Status label with icon + text, so state is never conveyed by colour alone.
 * Unknown values fall back to a neutral badge showing the raw value.
 */
const STATUS = {
  // connection
  ONLINE: { bg: 'success', icon: 'check-circle-fill', label: 'Online' },
  OFFLINE: { bg: 'danger', icon: 'x-octagon-fill', label: 'Offline' },
  NO_AGENT: { bg: 'secondary', icon: 'dash-circle', label: 'No agent' },
  // monitoring status
  PENDING_AGENT: { bg: 'info', icon: 'hourglass-split', label: 'Pending agent' },
  ACTIVE: { bg: 'success', icon: 'activity', label: 'Active' },
  PAUSED: { bg: 'warning', icon: 'pause-circle-fill', label: 'Paused' },
  DECOMMISSIONED: { bg: 'dark', icon: 'archive-fill', label: 'Decommissioned' },
  // agent health
  HEALTHY: { bg: 'success', icon: 'heart-pulse-fill', label: 'Healthy' },
  DEGRADED: { bg: 'warning', icon: 'exclamation-triangle-fill', label: 'Degraded' },
  UNREACHABLE: { bg: 'danger', icon: 'wifi-off', label: 'Unreachable' },
  // audit results
  SUCCESS: { bg: 'success', icon: 'check-lg', label: 'Success' },
  FAILURE: { bg: 'danger', icon: 'x-lg', label: 'Failure' },
  DENIED: { bg: 'warning', icon: 'shield-x', label: 'Denied' },
  // module availability
  AVAILABLE: { bg: 'success', icon: 'check-circle', label: 'Available' },
  NOT_COLLECTED: { bg: 'secondary', icon: 'cloud-slash', label: 'Not collected' },
  NOT_CONFIGURED: { bg: 'secondary', icon: 'gear', label: 'Not configured' },
  UNSUPPORTED: { bg: 'dark', icon: 'slash-circle', label: 'Unsupported' },
  // user state
  USER_ACTIVE: { bg: 'success', icon: 'person-check-fill', label: 'Active' },
  USER_INACTIVE: { bg: 'secondary', icon: 'person-dash-fill', label: 'Inactive' },
  USER_LOCKED: { bg: 'warning', icon: 'lock-fill', label: 'Locked' },
};

export default function StatusBadge({ status, label }) {
  const s = STATUS[status] || { bg: 'light', icon: 'question-circle', label: status };
  const textClass = ['warning', 'info', 'light'].includes(s.bg) ? 'text-dark' : '';
  return (
    <span className={`badge text-bg-${s.bg} ${textClass} d-inline-flex align-items-center gap-1`}>
      <i className={`bi bi-${s.icon}`} aria-hidden="true" />
      {label || s.label}
    </span>
  );
}
