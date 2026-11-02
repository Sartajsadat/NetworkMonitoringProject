import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { appApi, firewallApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import { AppForm, IdentityText, SupportBadge } from './AppAccessPage.jsx';
import { ModeBadge, RuleStatusBadge, RuleSummary, ScopeNote, issueText } from './firewallText.jsx';

const MODES = ['BLOCK_INTERNET', 'ALLOW_ONLY', 'BLOCK_DESTINATIONS'];
const EXPIRY = { '1h': 3600, '24h': 86400, '7d': 7 * 86400, '30d': 30 * 86400 };

function PolicyForm({ app }) {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [mode, setMode] = useState('BLOCK_INTERNET');
  const [dests, setDests] = useState([]);
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [expiry, setExpiry] = useState('24h');
  const [preview, setPreview] = useState(null);
  const [ack, setAck] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const stale = () => { setPreview(null); setAck(false); };
  const setDest = (n, k, v) => { setDests((d) => d.map((x, i) => (i === n ? { ...x, [k]: v } : x))); stale(); };
  const body = () => ({
    applicationId: app.id, appMode: mode, reason: reason.trim(), reference: reference.trim() || null, acknowledgeWarnings: ack,
    expiresAt: expiry === 'none' ? null : new Date(Date.now() + EXPIRY[expiry] * 1000).toISOString(),
    destinations: dests.filter((d) => d.cidr.trim()).map((d) => ({ cidr: d.cidr.trim(), protocol: d.protocol, port: d.protocol === 'ANY' ? null : d.port.trim() || null })),
  });
  const doPreview = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try { setPreview(await appApi.preview(body())); setAck(false); } catch (err) { setError(err); } finally { setBusy(false); }
  };
  const submit = async () => {
    setBusy(true);
    setError(null);
    try { const r = await appApi.request(body()); navigate(`/ip-policies/${r.id}`); } catch (err) { setError(err); setBusy(false); }
  };
  const canSubmit = preview?.ok && (preview.warnings.length === 0 || ack) && !busy;
  return (
    <form onSubmit={doPreview} className="row g-3">
      <div className="col-lg-7">
        <fieldset>
          <legend className="form-label fs-6">{t('appAccess.policyType')}</legend>
          {MODES.map((m) => (
            <div className="form-check mb-2" key={m}>
              <input className="form-check-input" type="radio" id={`m-${m}`} checked={mode === m} onChange={() => { setMode(m); stale(); }} />
              <label className="form-check-label" htmlFor={`m-${m}`}><span className="fw-semibold">{t(`appAccess.mode.${m}`)}</span>
                <div className="small text-body-secondary">{t(`appAccess.modeHelp.${m}`)}</div></label>
            </div>
          ))}
        </fieldset>
        <div className="mt-2">
          <div className="form-label">{mode === 'BLOCK_DESTINATIONS' ? t('appAccess.destBlocked') : mode === 'ALLOW_ONLY' ? t('appAccess.destAllowed') : t('appAccess.destExceptions')}</div>
          {dests.map((d, n) => (
            <div className="row g-2 mb-2" key={n}>
              <div className="col-5"><input className="form-control form-control-sm nm-ltr" dir="ltr" aria-label={t('appAccess.destAddress')} placeholder="10.20.0.15"
                value={d.cidr} onChange={(e) => setDest(n, 'cidr', e.target.value)} /></div>
              <div className="col-3"><select className="form-select form-select-sm" aria-label={t('fwRules.protocol')} value={d.protocol}
                onChange={(e) => setDest(n, 'protocol', e.target.value)}>
                <option value="ANY">{t('fwRules.anyProtocol')}</option><option value="TCP">TCP</option><option value="UDP">UDP</option></select></div>
              <div className="col-3"><input className="form-control form-control-sm nm-ltr" dir="ltr" aria-label={t('appAccess.destPort')} placeholder="443"
                value={d.port} disabled={d.protocol === 'ANY'} onChange={(e) => setDest(n, 'port', e.target.value)} /></div>
              <div className="col-1"><button type="button" className="btn btn-sm btn-outline-secondary" aria-label={t('common.delete')}
                onClick={() => { setDests((x) => x.filter((_, i) => i !== n)); stale(); }}><i className="bi bi-x" aria-hidden="true" /></button></div>
            </div>
          ))}
          <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => { setDests((x) => [...x, { cidr: '', protocol: 'TCP', port: '' }]); stale(); }}>
            <i className="bi bi-plus" aria-hidden="true" />{t('appAccess.addDest')}</button>
        </div>
        <div className="row g-3 mt-1">
          <div className="col-sm-6"><label className="form-label" htmlFor="p-exp">{t('fwRules.expires')}</label>
            <select id="p-exp" className="form-select" value={expiry} onChange={(e) => { setExpiry(e.target.value); stale(); }}>
              {['1h', '24h', '7d', '30d', 'none'].map((x) => <option key={x} value={x}>{t(`fwRules.expiry.${x}`)}</option>)}</select></div>
          <div className="col-sm-6"><label className="form-label" htmlFor="p-ref">{t('fwRules.reference')}</label>
            <input id="p-ref" className="form-control" maxLength={120} value={reference} onChange={(e) => { setReference(e.target.value); stale(); }} /></div>
          <div className="col-12"><label className="form-label" htmlFor="p-reason">{t('fwRules.reason')}</label>
            <textarea id="p-reason" className="form-control" required minLength={10} maxLength={500} rows={2} value={reason}
              onChange={(e) => { setReason(e.target.value); stale(); }} placeholder={t('appAccess.reasonHint')} /></div>
          <div className="col-12"><button type="submit" className="btn btn-outline-primary" disabled={busy}>
            <i className="bi bi-eye me-1" aria-hidden="true" />{t('fwRules.preview')}</button></div>
        </div>
      </div>
      <div className="col-lg-5">
        <h3 className="h6">{t('fwRules.previewTitle')}</h3>
        {!preview && <p className="small text-body-secondary">{t('fwRules.previewFirst')}</p>}
        {preview && (
          <>
            <ScopeNote app={app.name} name={preview.targetName} className="mb-3" />
            {preview.unsupported && <div className="alert alert-secondary small"><span className="fw-semibold">{t('appAccess.notEnforceable')}</span> {preview.unsupported}</div>}
            <div className="mb-2 small">{t('fw.mode')}: <ModeBadge mode={preview.mode} enforcing={preview.mode === 'ENFORCE'} /></div>
            {preview.command && <pre className="nm-mono small bg-body-tertiary p-2 rounded mb-3" dir="ltr" style={{ whiteSpace: 'pre-wrap' }}>{preview.command}</pre>}
            {preview.errors.length > 0 && <div className="alert alert-danger small" role="alert"><div className="fw-semibold mb-1">{t('fwRules.refused')}</div>
              <ul className="mb-0">{preview.errors.map((x, n) => <li key={n}>{issueText(t, x)}</li>)}</ul></div>}
            {!preview.errors.length && preview.warnings.length > 0 && (
              <div className="alert alert-warning small"><div className="fw-semibold mb-1">{t('fwRules.warnings')}</div>
                <ul className="mb-2">{preview.warnings.map((x, n) => <li key={n}>{issueText(t, x)}</li>)}</ul>
                <div className="form-check"><input id="p-ack" type="checkbox" className="form-check-input" checked={ack} onChange={(e) => setAck(e.target.checked)} />
                  <label className="form-check-label" htmlFor="p-ack">{t('fwRules.acknowledge')}</label></div></div>)}
            {preview.ok && <p className="small text-body-secondary">{t('fwRules.approvalNote')}</p>}
            <button type="button" className="btn btn-primary w-100" disabled={!canSubmit} onClick={submit}>
              <i className="bi bi-send me-1" aria-hidden="true" />{t('fwRules.submit')}</button>
          </>
        )}
        <div className="mt-3"><ErrorAlert error={error} /></div>
      </div>
    </form>
  );
}

export default function AppDetailPage() {
  const { id } = useParams();
  const { t } = useI18n();
  const { can } = useAuth();
  const { data: a, error, loading, reload } = useApi(() => appApi.get(id), [id]);
  const policies = useApi(() => firewallApi.rules({ applicationId: id, size: 50 }), [id]);
  const [editing, setEditing] = useState(false);
  const [requesting, setRequesting] = useState(false);
  if (!a) return <>{loading && <LoadingSpinner />}<ErrorAlert error={error} onRetry={reload} /></>;
  return (
    <>
      <PageHeader title={a.name} subtitle={<IdentityText kind={a.identityKind} value={a.identityValue} />}
        breadcrumbs={[{ label: t('appAccess.title'), to: '/app-access' }, { label: a.name }]}
        actions={can(PERMISSIONS.manageFirewall) && <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEditing(true)}>{t('common.edit')}</button>} />
      <div className="row g-3 mb-3">
        <div className="col-lg-6"><div className="card h-100"><div className="card-body">
          <h2 className="h6">{t('appAccess.support')}</h2>
          <SupportBadge support={a.support} />
          <p className="small mt-2 mb-0">{a.support?.supported ? (a.support.enforcing ? t('appAccess.supportEnforce') : t('appAccess.supportDryRun')) : a.support?.reason}</p>
        </div></div></div>
        <div className="col-lg-6"><div className="card h-100"><div className="card-body">
          <h2 className="h6">{t('appAccess.identity')}</h2>
          <p className="small mb-1"><Link to={`/servers/${a.serverId}`}>{a.serverName}</Link> · <IdentityText kind={a.identityKind} value={a.identityValue} /></p>
          <p className="small mb-0">{a.seenProcesses?.length ? t('appAccess.seenRunning', { list: a.seenProcesses.join(', ') }) : t('appAccess.notSeenLong')}</p>
          {a.identityKind === 'LINUX_USER' && <p className="small text-body-secondary mt-2 mb-0">{t('appAccess.help.LINUX_USER')}</p>}
        </div></div></div>
      </div>
      <div className="card mb-3"><div className="card-body">
        <div className="d-flex justify-content-between align-items-center mb-2">
          <h2 className="h6 mb-0">{t('appAccess.policies')}</h2>
          {can(PERMISSIONS.requestFirewallRules) && a.support?.supported && !requesting && (
            <button type="button" className="btn btn-sm btn-primary" onClick={() => setRequesting(true)}>{t('appAccess.requestPolicy')}</button>)}
        </div>
        {policies.data?.content?.length === 0 && <p className="small text-body-secondary mb-0">{t('appAccess.noPolicies')}</p>}
        {policies.data?.content?.length > 0 && (
          <table className="table nm-table mb-0 align-middle"><tbody>
            {policies.data.content.map((r) => (
              <tr key={r.id}><td><Link to={`/ip-policies/${r.id}`}><RuleSummary rule={r} /></Link></td><td><RuleStatusBadge status={r.status} /></td>
                <td className="small">{r.lastVerifiedAt ? <>{t('appAccess.lastVerified')} <TimeAgo value={r.lastVerifiedAt} /></> : '—'}</td></tr>))}
          </tbody></table>)}
      </div></div>
      {requesting && <div className="card"><div className="card-body"><h2 className="h6">{t('appAccess.requestPolicy')}</h2><PolicyForm app={a} /></div></div>}
      {editing && <AppForm app={a} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); reload(); }} />}
    </>
  );
}
