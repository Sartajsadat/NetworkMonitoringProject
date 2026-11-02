import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { firewallApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import { ModeBadge, ScopeNote, issueText } from './firewallText.jsx';

const EXPIRY = { '1h': 3600, '4h': 4 * 3600, '24h': 86400, '7d': 7 * 86400, '30d': 30 * 86400 };

function expiresAt(choice, custom) {
  if (choice === 'none') return null;
  if (choice === 'custom') return custom ? new Date(custom).toISOString() : null;
  return new Date(Date.now() + EXPIRY[choice] * 1000).toISOString();
}

export default function RuleRequestPage() {
  const { t } = useI18n();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const targets = useApi(() => firewallApi.integrations(), []);
  const [f, setF] = useState({
    integrationId: params.get('integrationId') || '', action: 'BLOCK', direction: 'INBOUND', remote: params.get('remote') || '', local: '',
    protocol: 'TCP', localPort: '', remotePort: '', reason: '', reference: '', expiry: '24h', custom: '',
  });
  const [preview, setPreview] = useState(null);
  const [ack, setAck] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const target = useMemo(() => (targets.data || []).find((i) => i.id === f.integrationId), [targets.data, f.integrationId]);
  const set = (k) => (e) => {
    setF((x) => ({ ...x, [k]: e.target.value }));
    setPreview(null); // any change makes the preview stale
    setAck(false);
  };
  const portsAllowed = f.protocol === 'TCP' || f.protocol === 'UDP';
  const body = () => ({
    integrationId: f.integrationId, action: f.action, direction: f.direction, remote: f.remote.trim(), local: f.local.trim() || null,
    protocol: f.protocol, localPort: portsAllowed ? f.localPort.trim() || null : null, remotePort: portsAllowed ? f.remotePort.trim() || null : null,
    reason: f.reason.trim(), reference: f.reference.trim() || null, expiresAt: expiresAt(f.expiry, f.custom), acknowledgeWarnings: ack,
  });
  const doPreview = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setPreview(await firewallApi.preview(body()));
      setAck(false);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await firewallApi.request(body());
      navigate(`/ip-policies/${r.id}`);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  const canSubmit = preview?.ok && (preview.warnings.length === 0 || ack) && !busy;
  return (
    <>
      <PageHeader title={t('fwRules.new')} subtitle={t('fwRules.newSubtitle')}
        breadcrumbs={[{ label: t('fwRules.title'), to: '/ip-policies' }, { label: t('fwRules.new') }]} />
      <form onSubmit={doPreview} className="row g-3">
        <div className="col-lg-7">
          <div className="card"><div className="card-body row g-3">
            <div className="col-12"><label className="form-label" htmlFor="q-target">{t('fw.target')}</label>
              <select id="q-target" className="form-select" required value={f.integrationId} onChange={set('integrationId')}>
                <option value="">{t('fwRules.chooseTarget')}</option>
                {(targets.data || []).map((i) => (
                  <option key={i.id} value={i.id}>{i.kind === 'SERVER_AGENT' ? `${i.serverName} — ${t('fw.kind.SERVER_AGENT')}` : `${i.name} — ${t('fw.kind.CENTRAL_DRY_RUN')}`}</option>
                ))}
              </select>
              {targets.data && targets.data.length === 0 && <div className="form-text">{t('fwRules.noTargets')}</div>}
            </div>
            {target && <div className="col-12"><ScopeNote kind={target.kind} name={target.kind === 'SERVER_AGENT' ? target.serverName : target.name} /></div>}
            <div className="col-sm-6"><label className="form-label" htmlFor="q-action">{t('fwRules.action')}</label>
              <select id="q-action" className="form-select" value={f.action} onChange={set('action')}>
                <option value="BLOCK">{t('fw.action.BLOCK')}</option><option value="ALLOW">{t('fw.action.ALLOW')}</option></select></div>
            <div className="col-sm-6"><label className="form-label" htmlFor="q-dir">{t('fwRules.direction')}</label>
              <select id="q-dir" className="form-select" value={f.direction} onChange={set('direction')}>
                <option value="INBOUND">{t('fwRules.dir.INBOUND')}</option><option value="OUTBOUND">{t('fwRules.dir.OUTBOUND')}</option></select></div>
            <div className="col-sm-6"><label className="form-label" htmlFor="q-remote">{t('fwRules.remote')}</label>
              <input id="q-remote" className="form-control nm-ltr" dir="ltr" required maxLength={50} value={f.remote} onChange={set('remote')}
                placeholder="203.0.113.0/24" />
              <div className="form-text">{t('fwRules.remoteHelp')}</div></div>
            <div className="col-sm-6"><label className="form-label" htmlFor="q-local">{t('fwRules.localAddr')}</label>
              <input id="q-local" className="form-control nm-ltr" dir="ltr" maxLength={50} value={f.local} onChange={set('local')} placeholder={t('fwRules.any')} /></div>
            <div className="col-sm-4"><label className="form-label" htmlFor="q-proto">{t('fwRules.protocol')}</label>
              <select id="q-proto" className="form-select" value={f.protocol} onChange={set('protocol')} dir="ltr">
                {['TCP', 'UDP', 'ICMP', 'ANY'].map((p) => <option key={p} value={p}>{p === 'ANY' ? t('fwRules.anyProtocol') : p}</option>)}</select></div>
            <div className="col-sm-4"><label className="form-label" htmlFor="q-lport">{t('fw.localPort')}</label>
              <input id="q-lport" className="form-control nm-ltr" dir="ltr" maxLength={11} value={f.localPort} onChange={set('localPort')}
                disabled={!portsAllowed} placeholder="443 / 8000-8080" /></div>
            <div className="col-sm-4"><label className="form-label" htmlFor="q-rport">{t('fw.remotePort')}</label>
              <input id="q-rport" className="form-control nm-ltr" dir="ltr" maxLength={11} value={f.remotePort} onChange={set('remotePort')}
                disabled={!portsAllowed} placeholder={t('fwRules.any')} /></div>
            <div className="col-sm-6"><label className="form-label" htmlFor="q-exp">{t('fwRules.expires')}</label>
              <select id="q-exp" className="form-select" value={f.expiry} onChange={set('expiry')}>
                {['1h', '4h', '24h', '7d', '30d', 'custom', 'none'].map((x) => <option key={x} value={x}>{t(`fwRules.expiry.${x}`)}</option>)}</select></div>
            {f.expiry === 'custom' && (
              <div className="col-sm-6"><label className="form-label" htmlFor="q-custom">{t('fwRules.expiryAt')}</label>
                <input id="q-custom" type="datetime-local" dir="ltr" className="form-control" required value={f.custom} onChange={set('custom')} /></div>)}
            <div className="col-12"><label className="form-label" htmlFor="q-reason">{t('fwRules.reason')}</label>
              <textarea id="q-reason" className="form-control" required minLength={10} maxLength={500} rows={2} value={f.reason} onChange={set('reason')}
                placeholder={t('fwRules.reasonHint')} /></div>
            <div className="col-sm-6"><label className="form-label" htmlFor="q-ref">{t('fwRules.reference')}</label>
              <input id="q-ref" className="form-control" maxLength={120} value={f.reference} onChange={set('reference')} placeholder="INC-2026-0142" /></div>
            <div className="col-12 d-flex gap-2">
              <button type="submit" className="btn btn-outline-primary" disabled={busy}><i className="bi bi-eye me-1" aria-hidden="true" />{t('fwRules.preview')}</button>
            </div>
          </div></div>
        </div>
        <div className="col-lg-5">
          <div className="card h-100"><div className="card-body">
            <h2 className="h6">{t('fwRules.previewTitle')}</h2>
            {!preview && <p className="small text-body-secondary">{t('fwRules.previewFirst')}</p>}
            {preview && (
              <>
                <ScopeNote kind={preview.scopeKind} name={preview.targetName} className="mb-3" />
                <div className="mb-2 small">{t('fw.mode')}: <ModeBadge mode={preview.mode} enforcing={preview.mode === 'ENFORCE'} /></div>
                {preview.command && (
                  <>
                    <div className="small text-body-secondary">{t('fwRules.command')}</div>
                    <pre className="nm-mono small bg-body-tertiary p-2 rounded mb-3" dir="ltr" style={{ whiteSpace: 'pre-wrap' }}>{preview.command}</pre>
                  </>
                )}
                {preview.errors.length > 0 && (
                  <div className="alert alert-danger small" role="alert">
                    <div className="fw-semibold mb-1">{t('fwRules.refused')}</div>
                    <ul className="mb-0">{preview.errors.map((x, n) => <li key={n}>{issueText(t, x)}</li>)}</ul>
                  </div>
                )}
                {preview.ok && preview.warnings.length > 0 && (
                  <div className="alert alert-warning small">
                    <div className="fw-semibold mb-1">{t('fwRules.warnings')}</div>
                    <ul className="mb-2">{preview.warnings.map((x, n) => <li key={n}>{issueText(t, x)}</li>)}</ul>
                    <div className="form-check">
                      <input id="q-ack" type="checkbox" className="form-check-input" checked={ack} onChange={(e) => setAck(e.target.checked)} />
                      <label className="form-check-label" htmlFor="q-ack">{t('fwRules.acknowledge')}</label>
                    </div>
                  </div>
                )}
                {preview.ok && <p className="small text-body-secondary">{t('fwRules.approvalNote')}</p>}
                <button type="button" className="btn btn-primary w-100" disabled={!canSubmit} onClick={submit}>
                  <i className="bi bi-send me-1" aria-hidden="true" />{t('fwRules.submit')}</button>
              </>
            )}
            <div className="mt-3"><ErrorAlert error={error} /></div>
          </div></div>
        </div>
      </form>
    </>
  );
}
