import { useI18n } from '../../i18n/I18nContext.jsx';

/**
 * Rule statuses. The brief's five states (PENDING, APPLIED, FAILED, EXPIRED, UNVERIFIED) are the
 * groups shown to users; the detailed status is kept in the label.
 */
export const RULE_STATUS = {
  REQUESTED: { group: 'PENDING', bg: 'info', icon: 'hourglass-split' },
  APPROVED: { group: 'PENDING', bg: 'info', icon: 'send' },
  APPLIED: { group: 'APPLIED', bg: 'success', icon: 'shield-fill-check' },
  DRY_RUN: { group: 'DRY_RUN', bg: 'secondary', icon: 'eye' },
  FAILED: { group: 'FAILED', bg: 'danger', icon: 'x-octagon-fill' },
  UNVERIFIED: { group: 'UNVERIFIED', bg: 'warning', icon: 'question-diamond-fill' },
  REMOVAL_PENDING: { group: 'PENDING', bg: 'info', icon: 'hourglass-bottom' },
  REMOVED: { group: 'ENDED', bg: 'light', icon: 'trash' },
  EXPIRED: { group: 'EXPIRED', bg: 'light', icon: 'clock' },
  REJECTED: { group: 'ENDED', bg: 'light', icon: 'hand-thumbs-down' },
  CANCELLED: { group: 'ENDED', bg: 'light', icon: 'x-circle' },
};

/** Status filters on the rules page. */
export const RULE_FILTERS = {
  pending: ['REQUESTED', 'APPROVED', 'REMOVAL_PENDING'],
  active: ['APPLIED', 'DRY_RUN', 'UNVERIFIED'],
  problems: ['FAILED', 'UNVERIFIED'],
  ended: ['REMOVED', 'EXPIRED', 'REJECTED', 'CANCELLED'],
  all: [],
};

export function RuleStatusBadge({ status }) {
  const { t } = useI18n();
  const s = RULE_STATUS[status] || { bg: 'light', icon: 'question-circle' };
  const textClass = ['warning', 'info', 'light'].includes(s.bg) ? 'text-dark' : '';
  return (
    <span className={`badge text-bg-${s.bg} ${textClass} d-inline-flex align-items-center gap-1`}>
      <i className={`bi bi-${s.icon}`} aria-hidden="true" />
      {t(`fwStatus.${status}`)}
    </span>
  );
}

export function ModeBadge({ mode, enforcing }) {
  const { t } = useI18n();
  if (mode === 'ENFORCE') {
    return (
      <span className={`badge ${enforcing ? 'text-bg-danger' : 'text-bg-warning text-dark'} d-inline-flex align-items-center gap-1`}>
        <i className="bi bi-lightning-charge-fill" aria-hidden="true" />
        {enforcing ? t('fw.mode.ENFORCE') : t('fw.mode.enforceBlocked')}
      </span>
    );
  }
  return <span className="badge text-bg-secondary  d-inline-flex align-items-center gap-1"><i className="bi bi-eye" aria-hidden="true" />{t('fw.mode.DRY_RUN')}</span>;
}

/** "block from 203.0.113.0/24 TCP port 443" in the user's language; addresses stay left-to-right. */
export function RuleSummary({ rule }) {
  const { t } = useI18n();
  if (rule.applicationId) {
    return (
      <span>
        <i className="bi bi-app-indicator me-1" aria-hidden="true" />
        <span className="fw-semibold">{rule.applicationLabel}</span>{' · '}
        <span className={rule.appMode === 'ALLOW_ONLY' ? 'text-success fw-semibold' : 'text-danger fw-semibold'}>{t(`appAccess.mode.${rule.appMode}`)}</span>
        {rule.destinations?.length > 0 && <> · {t('appAccess.destCount', { n: rule.destinations.length })}</>}
      </span>
    );
  }
  return (
    <span>
      <span className={`fw-semibold ${rule.action === 'BLOCK' ? 'text-danger' : 'text-success'}`}>{t(`fw.action.${rule.action}`)}</span>{' '}
      {t(`fw.direction.${rule.direction}`)}{' '}
      <span className="nm-mono" dir="ltr">{rule.remoteCidr}</span>
      {rule.protocol && rule.protocol !== 'ANY' && <> · <span dir="ltr">{rule.protocol}</span></>}
      {rule.localPort && <> · {t('fw.localPort')} <span className="nm-mono" dir="ltr">{rule.localPort}</span></>}
      {rule.remotePort && <> · {t('fw.remotePort')} <span className="nm-mono" dir="ltr">{rule.remotePort}</span></>}
      {rule.localCidr && <> · {t('fw.local')} <span className="nm-mono" dir="ltr">{rule.localCidr}</span></>}
    </span>
  );
}

/** Where the rule takes effect. Never presented as a network-wide block for a server rule. */
export function ScopeNote({ kind, name, app, className = '' }) {
  const { t } = useI18n();
  if (app) {
    return (
      <div className={`alert alert-info d-flex gap-2 align-items-start mb-0 ${className}`} role="note">
        <i className="bi bi-app-indicator fs-5" aria-hidden="true" />
        <div>
          <div className="fw-semibold">{t('appAccess.scopeTitle', { app, name: name || '?' })}</div>
          <div className="small">{t('appAccess.scopeText')}</div>
        </div>
      </div>
    );
  }
  const server = kind === 'SERVER_AGENT' || kind === 'SERVER';
  return (
    <div className={`alert ${server ? 'alert-info' : 'alert-secondary'} d-flex gap-2 align-items-start mb-0 ${className}`} role="note">
      <i className={`bi ${server ? 'bi-hdd-network' : 'bi-diagram-3'} fs-5`} aria-hidden="true" />
      <div>
        <div className="fw-semibold">{server ? t('fw.scope.serverTitle', { name: name || '?' }) : t('fw.scope.centralTitle', { name: name || '?' })}</div>
        <div className="small">{server ? t('fw.scope.serverText') : t('fw.scope.centralText')}</div>
      </div>
    </div>
  );
}

/** A validation finding from the platform, translated by its code. */
export function issueText(t, issue) {
  const key = `fwIssue.${issue.code}`;
  const tr = t(key, { p: issue.param || '' });
  return tr === key ? issue.text : tr;
}
