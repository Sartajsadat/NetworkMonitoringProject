import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { firewallApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Modal from '../../components/Modal.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import { ModeBadge, RuleStatusBadge, RuleSummary, ScopeNote } from './firewallText.jsx';

const REMOVABLE = ['APPLIED', 'DRY_RUN', 'UNVERIFIED', 'FAILED', 'APPROVED'];

function Decision({ kind, rule, onClose, onDone }) {
  const { t } = useI18n();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const required = kind !== 'approve';
  const go = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (kind === 'approve') await firewallApi.approve(rule.id, text || null);
      else if (kind === 'reject') await firewallApi.reject(rule.id, text);
      else await firewallApi.remove(rule.id, text);
      onDone();
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  return (
    <Modal show title={t(`rule.${kind}Title`)} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
        <button type="submit" form="rule-decision" className={`btn ${kind === 'approve' ? 'btn-success' : 'btn-danger'}`} disabled={busy}>
          {t(`rule.${kind}`)}</button></>)}>
      <form id="rule-decision" onSubmit={go}>
        <p><RuleSummary rule={rule} /></p>
        <ScopeNote kind={rule.integrationKind} name={rule.integrationKind === 'SERVER_AGENT' ? rule.serverName : rule.integrationName} className="mb-3" />
        {kind === 'approve' && <div className="alert alert-warning small">{t('rule.approveText')}</div>}
        {kind === 'remove' && <p className="small">{t('rule.removeText')}</p>}
        <label className="form-label" htmlFor="d-text">{required ? t('rule.reasonRequired') : t('rule.comment')}</label>
        <textarea id="d-text" className="form-control" rows={2} maxLength={1000} required={required} minLength={required ? 5 : 0}
          value={text} onChange={(e) => setText(e.target.value)} />
        <div className="mt-3"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}

function Row({ label, children }) {
  return <div className="row py-1"><div className="col-sm-4 text-body-secondary small">{label}</div><div className="col-sm-8">{children}</div></div>;
}

export default function RuleDetailPage() {
  const { id } = useParams();
  const { t, formatDateTime } = useI18n();
  const { user, can } = useAuth();
  const { data, error, loading, reload } = useApi(() => firewallApi.rule(id), [id]);
  const [dialog, setDialog] = useState(null);
  const [cancelError, setCancelError] = useState(null);
  if (!data) return <>{loading && <LoadingSpinner />}<ErrorAlert error={error} onRetry={reload} /></>;
  const r = data.rule;
  const own = user?.username === r.requestedBy;
  const canDecide = r.status === 'REQUESTED' && can(PERMISSIONS.manageFirewall) && !own;
  const canCancel = r.status === 'REQUESTED' && (own || can(PERMISSIONS.manageFirewall));
  const canRemove = REMOVABLE.includes(r.status) && can(PERMISSIONS.manageFirewall);
  const when = (v) => (v ? formatDateTime(v) : '—');
  const cancel = async () => {
    setCancelError(null);
    try { await firewallApi.cancel(r.id); reload(); } catch (e) { setCancelError(e); }
  };
  return (
    <>
      <PageHeader title={t('rule.title')} subtitle={<RuleSummary rule={r} />}
        breadcrumbs={[{ label: t('fwRules.title'), to: '/ip-policies' }, { label: r.tag }]}
        actions={(
          <div className="d-flex gap-2 flex-wrap">
            {canDecide && <button type="button" className="btn btn-sm btn-success" onClick={() => setDialog('approve')}>{t('rule.approve')}</button>}
            {canDecide && <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDialog('reject')}>{t('rule.reject')}</button>}
            {canCancel && <button type="button" className="btn btn-sm btn-outline-secondary" onClick={cancel}>{t('rule.cancel')}</button>}
            {canRemove && <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDialog('remove')}>{t('rule.remove')}</button>}
          </div>
        )} />
      <ErrorAlert error={cancelError} />
      {r.status === 'REQUESTED' && own && can(PERMISSIONS.manageFirewall) && (
        <div className="alert alert-info small">{t('rule.ownRequest')}</div>)}
      <div className="row g-3">
        <div className="col-lg-7">
          <div className="card mb-3"><div className="card-body">
            <div className="d-flex align-items-center gap-2 mb-2"><RuleStatusBadge status={r.status} />
              {r.modeAtApply && <ModeBadge mode={r.modeAtApply} enforcing={r.modeAtApply === 'ENFORCE'} />}</div>
            <p className="small mb-2">{t(`fwStatusHelp.${r.status}`)}</p>
            {r.statusMessage && <div className="small bg-body-tertiary p-2 rounded nm-ltr" dir="ltr">{r.statusMessage}</div>}
          </div></div>
          <ScopeNote kind={r.integrationKind} name={r.integrationKind === 'SERVER_AGENT' ? r.serverName : r.integrationName} className="mb-3" />
          <div className="card mb-3"><div className="card-body">
            <h2 className="h6">{t('rule.details')}</h2>
            <Row label={t('fw.target')}>{r.integrationKind === 'SERVER_AGENT'
              ? <Link to={`/servers/${r.serverId}`}>{r.serverName}</Link> : r.integrationName}</Row>
            <Row label={t('fwRules.rule')}><RuleSummary rule={r} /></Row>
            <Row label={t('fwRules.reason')}>{r.reason}</Row>
            {r.reference && <Row label={t('fwRules.reference')}>{r.reference}</Row>}
            <Row label={t('fwRules.expires')}>{r.expiresAt ? when(r.expiresAt) : t('fwRules.noExpiry')}</Row>
            <Row label={t('rule.tag')}><span className="nm-mono" dir="ltr">netmon:{r.tag}</span></Row>
            {r.warnings.length > 0 && (
              <Row label={t('rule.ackWarnings')}><ul className="small mb-0 ps-3">{r.warnings.map((w, n) => <li key={n} dir="ltr" className="text-start">{w}</li>)}</ul></Row>)}
          </div></div>
          <div className="card"><div className="card-body">
            <h2 className="h6">{t('rule.command')}</h2>
            <pre className="nm-mono small bg-body-tertiary p-2 rounded mb-2" dir="ltr" style={{ whiteSpace: 'pre-wrap' }}>{r.preview}</pre>
            {r.agentCommand && (<>
              <div className="small text-body-secondary">{t('rule.agentCommand')}</div>
              <pre className="nm-mono small bg-body-tertiary p-2 rounded mb-0" dir="ltr" style={{ whiteSpace: 'pre-wrap' }}>{r.agentCommand}</pre>
            </>)}
          </div></div>
        </div>
        <div className="col-lg-5">
          <div className="card mb-3"><div className="card-body">
            <h2 className="h6">{t('rule.history')}</h2>
            <ul className="list-unstyled small mb-0 nm-history">
              <li><i className="bi bi-person me-1" aria-hidden="true" />{t('rule.h.requested', { who: r.requestedBy })}<div className="text-body-secondary">{when(r.requestedAt)}</div></li>
              {data.approvals.map((a, n) => (
                <li key={n} className="mt-2"><i className={`bi ${a.decision === 'APPROVED' ? 'bi-check2-circle text-success' : 'bi-x-circle text-danger'} me-1`} aria-hidden="true" />
                  {t(`rule.h.${a.decision}`, { who: a.decidedBy })}<div className="text-body-secondary">{when(a.decidedAt)}</div>
                  {a.comment && <div className="fst-italic">“{a.comment}”</div>}</li>
              ))}
              {r.appliedAt && <li className="mt-2"><i className="bi bi-shield-check me-1" aria-hidden="true" />{t(r.modeAtApply === 'ENFORCE' ? 'rule.h.applied' : 'rule.h.checked')}<div className="text-body-secondary">{when(r.appliedAt)}</div></li>}
              {r.lastVerifiedAt && r.status === 'APPLIED' && <li className="mt-2"><i className="bi bi-eye me-1" aria-hidden="true" />{t('rule.h.verified')}<div className="text-body-secondary">{when(r.lastVerifiedAt)}</div></li>}
              {r.removalRequestedAt && <li className="mt-2"><i className="bi bi-trash me-1" aria-hidden="true" />{r.expiring ? t('rule.h.expiring') : t('rule.h.removalRequested', { who: r.removalRequestedBy })}
                <div className="text-body-secondary">{when(r.removalRequestedAt)}</div>{r.removalReason && !r.expiring && <div className="fst-italic">“{r.removalReason}”</div>}</li>}
              {r.removedAt && <li className="mt-2"><i className="bi bi-check-all me-1" aria-hidden="true" />{t(r.status === 'EXPIRED' ? 'rule.h.expired' : 'rule.h.removed')}<div className="text-body-secondary">{when(r.removedAt)}</div></li>}
            </ul>
            <Link className="btn btn-sm btn-outline-secondary mt-3" to={`/timeline?correlationId=${r.id}`}>
              <i className="bi bi-clock-history me-1" aria-hidden="true" />{t('rule.timeline')}</Link>
          </div></div>
          <div className="card"><div className="card-body small">
            <h2 className="h6">{t('rule.recovery')}</h2>
            <p>{t('rule.recoveryText')}</p>
            <dl className="mb-0">
              <dt className="fw-normal text-body-secondary">nftables</dt><dd><code className="nm-ltr d-block" dir="ltr">sudo nft delete table inet netmon</code></dd>
              <dt className="fw-normal text-body-secondary">UFW</dt><dd><code className="nm-ltr d-block" dir="ltr">sudo ufw status numbered</code><code className="nm-ltr d-block" dir="ltr">sudo ufw delete N</code></dd>
              <dt className="fw-normal text-body-secondary">Windows</dt><dd className="mb-0"><code className="nm-ltr d-block" dir="ltr">Remove-NetFirewallRule -Group NetMon</code></dd>
            </dl>
          </div></div>
        </div>
      </div>
      {dialog && <Decision kind={dialog} rule={r} onClose={() => setDialog(null)} onDone={() => { setDialog(null); reload(); }} />}
    </>
  );
}
