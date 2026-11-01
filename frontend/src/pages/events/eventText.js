// Turns events and alerts into text in the reader's language. The backend also stores an English
// summary; it is the fallback for event types this file does not know.

export const SEVERITIES = ['INFO', 'WARNING', 'ERROR', 'CRITICAL'];
export const CATEGORIES = ['PERFORMANCE', 'AVAILABILITY', 'SERVICE', 'NETWORK', 'CAPTURE', 'SECURITY', 'AGENT', 'SYSTEM'];

export const SEVERITY_STYLE = {
  INFO: { bg: 'info', icon: 'info-circle-fill' },
  WARNING: { bg: 'warning', icon: 'exclamation-triangle-fill' },
  ERROR: { bg: 'danger', icon: 'x-octagon-fill' },
  CRITICAL: { bg: 'dark', icon: 'radioactive' },
};

export const CATEGORY_ICON = {
  PERFORMANCE: 'speedometer', AVAILABILITY: 'plug', SERVICE: 'gear', NETWORK: 'ethernet', CAPTURE: 'record-circle',
  SECURITY: 'shield-lock', AGENT: 'broadcast', SYSTEM: 'hdd-network',
};

/** Value of an alert rule in its unit (percent, bits/s, ratio, ...). */
export function formatAlertValue(unit, v, { units, formatNumber, formatDuration, t }) {
  if (v === null || v === undefined) return '—';
  switch (unit) {
    case 'PERCENT': return units.percent(v);
    case 'BITS_PER_SECOND': return units.bits(v);
    case 'RATIO': return t('alertUnit.ratio', { n: formatNumber(Math.round(v * 10) / 10) });
    case 'PER_SECOND': return units.perSec(v);
    case 'SECONDS': return formatDuration(v);
    case 'COUNT': return formatNumber(Math.round(v));
    default: return formatNumber(v);
  }
}

export function windowText(seconds, { t, formatNumber, formatDuration }) {
  if (!seconds) return t('alert.noWindow');
  return seconds < 60 ? t('time.seconds', { s: formatNumber(seconds) }) : formatDuration(seconds);
}

/** Localized condition of an alert ("CPU averaged 96% over 5 min (threshold 90%)"). */
export function alertCondition(a, i18n) {
  const { t } = i18n;
  const key = `alert.condition.${a.ruleType}`;
  const text = t(key, {
    resource: a.resource || '',
    value: formatAlertValue(a.unit, a.lastValue, i18n),
    threshold: formatAlertValue(a.unit, a.threshold, i18n),
    window: windowText(a.windowSeconds, i18n),
  });
  return text === key ? a.condition : text;
}

const statusWord = (t, s) => (s ? t(`status.${s}`) : '');

/** Localized one-line summary of an event. */
export function eventSummary(e, i18n) {
  const { t, formatNumber, formatDuration } = i18n;
  const d = e.details || {};
  let vars = null;
  switch (e.type) {
    case 'SERVICE_STATE_CHANGED': vars = { service: d.service, from: statusWord(t, d.from), to: statusWord(t, d.to) }; break;
    case 'SERVICE_RESTARTED': vars = { service: d.service }; break;
    case 'SERVER_REBOOTED': vars = {}; break;
    case 'COLLECTOR_PROBLEM': vars = { collector: d.collector, status: statusWord(t, d.status), message: d.message || '' }; break;
    case 'COLLECTOR_RECOVERED': vars = { collector: d.collector }; break;
    case 'AGENT_DISCONNECTED': vars = { minutes: formatNumber(Math.round((d.thresholdSeconds || 0) / 60)) }; break;
    case 'AGENT_RECONNECTED': vars = { duration: formatDuration(d.offlineSeconds || 0) }; break;
    case 'AGENT_ENROLLED': case 'AGENT_REVOKED': vars = { by: d.by || '' }; break;
    case 'CAPTURE_REQUESTED': case 'CAPTURE_STOP_REQUESTED': vars = { name: d.name, by: d.by || '' }; break;
    case 'CAPTURE_STARTED': vars = { name: d.name, iface: d.interface }; break;
    case 'CAPTURE_COMPLETED': vars = { name: d.name, packets: d.packets == null ? '—' : formatNumber(d.packets) }; break;
    case 'CAPTURE_FAILED': vars = { name: d.name, message: d.message || '' }; break;
    case 'CAPTURE_CANCELLED': vars = { name: d.name }; break;
    case 'CAPTURE_FILES_DELETED': vars = { name: d.name }; break;
    case 'LOGIN_FAILED': case 'LOGIN_RATE_LIMITED': vars = { user: d.username || t('event.unknownAccount'), ip: e.ipAddress || '—' }; break;
    case 'ACCOUNT_LOCKED': vars = { user: d.username || '', ip: e.ipAddress || '—' }; break;
    case 'TOKEN_REUSE_DETECTED': case 'SETUP_ATTEMPT_FAILED': case 'AGENT_AUTH_FAILED': vars = { ip: e.ipAddress || '—' }; break;
    case 'ALERT_ACKNOWLEDGED': vars = { rule: d.rule, by: d.by, comment: d.comment ? `: ${d.comment}` : '' }; break;
    case 'ALERT_RESOLVED': vars = {
      rule: d.rule,
      by: d.by || t('alert.automatically'),
      // automatic notes are written by the platform in English; show the translated meaning instead
      resolution: d.by ? (d.resolution || '') : t('alert.resolution.AUTO'),
    }; break;
    case 'EVENTS_SUPPRESSED': vars = { n: formatNumber(d.limit) }; break;
    default:
      if (d.ruleType) {
        const unit = d.unit;
        return t('event.summary.ALERT_OPENED', {
          rule: d.rule,
          value: formatAlertValue(unit, d.value, i18n),
          threshold: formatAlertValue(unit, d.threshold, i18n),
          resource: e.resource ? ` (${e.resource})` : '',
        });
      }
  }
  if (!vars) return e.summary;
  const key = `event.summary.${e.type}`;
  const out = t(key, vars);
  return out === key ? e.summary : out;
}

export function eventTitle(e, t) {
  const key = `event.type.${e.type}`;
  const out = t(key);
  if (out !== key) return out;
  const rule = `alertType.${e.type}`;
  const r = t(rule);
  return r === rule ? e.type : r;
}

/** Range for "around this time" links: ±minutes, never in the future. */
export function around(iso, minutes) {
  const t = new Date(iso).getTime();
  const to = Math.min(Date.now(), t + minutes * 60000);
  return { from: new Date(t - minutes * 60000).toISOString(), to: new Date(to).toISOString() };
}
