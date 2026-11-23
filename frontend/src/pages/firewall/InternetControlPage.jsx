import { useCallback, useEffect, useState } from 'react';
import { internetApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';

const SITE_BADGE = {
  BLOCKED: 'text-bg-danger', BLOCKING: 'text-bg-warning', UNBLOCKING: 'text-bg-warning', ALLOWED: 'text-bg-success',
  OFF_ON_COMPUTER: 'text-bg-secondary',
};
const APP_BADGE = {
  APPLIED: 'text-bg-danger', APPROVED: 'text-bg-warning', REQUESTED: 'text-bg-info', DRY_RUN: 'text-bg-secondary',
  UNVERIFIED: 'text-bg-warning', REMOVAL_PENDING: 'text-bg-warning', FAILED: 'text-bg-danger',
};
const REFRESH_MS = 10000;
const LAST_COMPUTER = 'netmon.internet.computer';

function remembered() {
  try { return window.localStorage.getItem(LAST_COMPUTER) || ''; } catch { return ''; }
}

function remember(id) {
  try { window.localStorage.setItem(LAST_COMPUTER, id); } catch { /* not available: just not remembered */ }
}

function Switch({ id, checked, onChange, disabled, label }) {
  return (
    <div className="form-check form-switch mb-0">
      <input id={id} className="form-check-input" type="checkbox" role="switch" checked={checked} disabled={disabled}
        onChange={(e) => onChange(e.target.checked)} aria-label={label} style={{ width: '2.6em', height: '1.3em' }} />
    </div>
  );
}

function Websites({ view, onChange, busy }) {
  const { t } = useI18n();
  const [site, setSite] = useState('');
  const sites = view.sites;
  const add = async (e) => {
    e.preventDefault();
    if (await onChange(() => internetApi.addSite(view.serverId, site))) setSite('');
  };
  return (
    <div className="card h-100">
      <div className="card-header fw-semibold"><i className="bi bi-globe2 me-2" aria-hidden="true" />{t('internet.sites')}</div>
      <div className="card-body">
        {!sites.enabledOnComputer && (
          <div className="alert alert-warning small"><i className="bi bi-exclamation-triangle me-1" aria-hidden="true" />
            {t('internet.sitesOff')}<div className="text-body-secondary mt-1">{sites.message}</div></div>
        )}
        <form className="d-flex gap-2 mb-3" onSubmit={add}>
          <input className="form-control nm-ltr" dir="ltr" required maxLength={2000} value={site} onChange={(e) => setSite(e.target.value)}
            placeholder="youtube.com" aria-label={t('internet.siteName')} />
          <button type="submit" className="btn btn-danger text-nowrap" disabled={busy}>
            <i className="bi bi-slash-circle me-1" aria-hidden="true" />{t('internet.block')}</button>
        </form>
        {sites.list.length === 0 ? <div className="text-body-secondary small">{t('internet.noSites')}</div> : (
          <ul className="list-group">
            {sites.list.map((s) => (
              <li key={s.id} className="list-group-item d-flex align-items-center gap-3">
                <Switch id={`site-${s.id}`} checked={s.blocked} disabled={busy} label={t('internet.blockSite', { site: s.domain })}
                  onChange={(v) => onChange(() => internetApi.switchSite(s.id, v))} />
                <div className="flex-grow-1">
                  <div className="fw-semibold nm-ltr" dir="ltr">{s.domain}</div>
                  <span className={`badge ${SITE_BADGE[s.state] || 'text-bg-secondary'}`}>{t(`internet.siteState.${s.state}`)}</span>
                </div>
                <button type="button" className="btn btn-sm btn-outline-secondary" disabled={busy} aria-label={t('common.delete')}
                  onClick={() => onChange(() => internetApi.removeSite(s.id))}><i className="bi bi-trash" aria-hidden="true" /></button>
              </li>
            ))}
          </ul>
        )}
        <div className="form-text mt-2">{t('internet.sitesHelp')}</div>
      </div>
    </div>
  );
}

function Apps({ view, onChange, busy }) {
  const { t } = useI18n();
  const apps = view.apps;
  const [f, setF] = useState({ name: '', identity: '' });
  const kind = apps.identityKind;
  const add = async (e) => {
    e.preventDefault();
    if (await onChange(() => internetApi.addApp(view.serverId, f.name, f.identity))) setF({ name: '', identity: '' });
  };
  return (
    <div className="card h-100">
      <div className="card-header fw-semibold"><i className="bi bi-window-stack me-2" aria-hidden="true" />{t('internet.apps')}</div>
      <div className="card-body">
        {apps.message && <div className={`alert small ${apps.supported ? 'alert-info' : 'alert-warning'}`}>
          <i className="bi bi-info-circle me-1" aria-hidden="true" />{apps.message}</div>}
        {kind && (
          <form className="row g-2 mb-3" onSubmit={add}>
            <div className="col-sm-4"><input className="form-control" required maxLength={120} value={f.name} aria-label={t('internet.appName')}
              placeholder={t('internet.appName')} onChange={(e) => setF((x) => ({ ...x, name: e.target.value }))} /></div>
            <div className="col-sm-6"><input className="form-control nm-ltr" dir="ltr" required maxLength={260} value={f.identity}
              aria-label={t(`internet.identity.${kind}`)} placeholder={t(`internet.example.${kind}`)}
              onChange={(e) => setF((x) => ({ ...x, identity: e.target.value }))} /></div>
            <div className="col-sm-2 d-grid"><button type="submit" className="btn btn-primary" disabled={busy}>{t('internet.add')}</button></div>
            <div className="col-12 form-text mt-0">{t(`internet.help.${kind}`)}</div>
          </form>
        )}
        {apps.list.length === 0 ? <div className="text-body-secondary small">{t('internet.noApps')}</div> : (
          <ul className="list-group">
            {apps.list.map((a) => (
              <li key={a.id} className="list-group-item d-flex align-items-center gap-3">
                <Switch id={`app-${a.id}`} checked={!a.internetBlocked} disabled={busy || !apps.supported}
                  label={t('internet.appInternet', { name: a.name })} onChange={(v) => onChange(() => internetApi.switchApp(a.id, v))} />
                <div className="flex-grow-1 text-break">
                  <div className="fw-semibold">{a.name} <span className="small text-body-secondary fw-normal">
                    {a.internetBlocked ? t('internet.internetOff') : t('internet.internetOn')}</span></div>
                  <div className="small nm-mono nm-ltr text-body-secondary" dir="ltr">{a.identityValue}</div>
                  {a.status && <span className={`badge ${APP_BADGE[a.status] || 'text-bg-secondary'}`} title={a.message || ''}>
                    {t(`internet.appState.${a.status}`)}</span>}
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

export default function InternetControlPage() {
  const { t } = useI18n();
  const computers = useApi(() => internetApi.computers(), []);
  const [serverId, setServerId] = useState(remembered);
  const [view, setView] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const list = computers.data || [];
  // The computer picked last time, if it still exists; otherwise the first one.
  const selected = list.some((c) => c.id === serverId) ? serverId : list[0]?.id || '';

  const load = useCallback(async () => {
    if (!selected) return;
    try { setView(await internetApi.view(selected)); } catch (e) { setError(e); }
  }, [selected]);

  useEffect(() => {
    setView(null);
    setError(null);
    load();
    const timer = setInterval(load, REFRESH_MS); // shows when the computer has applied a change
    return () => clearInterval(timer);
  }, [load]);

  /** Runs one change; the API answers with the new state of the page. Returns true on success. */
  const change = async (call) => {
    setBusy(true);
    setError(null);
    try { setView(await call()); return true; } catch (e) { setError(e); return false; } finally { setBusy(false); }
  };

  return (
    <>
      <PageHeader title={t('internet.title')} subtitle={t('internet.subtitle')} breadcrumbs={[{ label: t('internet.title') }]} />
      <ErrorAlert error={computers.error} onRetry={computers.reload} />
      {!computers.data && computers.loading && <LoadingSpinner />}
      {computers.data && list.length === 0 && <div className="card"><EmptyState icon="pc-display" title={t('internet.noComputers')}>
        {t('internet.noComputersHint')}</EmptyState></div>}
      {list.length > 0 && (
        <div className="d-flex align-items-center gap-2 mb-3 flex-wrap">
          <label className="form-label mb-0 fw-semibold" htmlFor="ic-computer">{t('internet.computer')}</label>
          <select id="ic-computer" className="form-select w-auto" value={selected} onChange={(e) => { setServerId(e.target.value); remember(e.target.value); }}>
            {list.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          {view && <span className={`badge ${view.connection === 'ONLINE' ? 'text-bg-success' : 'text-bg-secondary'}`}>
            {t(`internet.connection.${view.connection}`)}</span>}
        </div>
      )}
      {view && !view.personalMode && <div className="alert alert-light border small">
        <i className="bi bi-people me-1" aria-hidden="true" />{t('internet.approvalNote')}</div>}
      <ErrorAlert error={error} />
      {selected && !view && !error && <LoadingSpinner />}
      {view && (
        <div className="row g-3">
          <div className="col-lg-6"><Websites view={view} onChange={change} busy={busy} /></div>
          <div className="col-lg-6"><Apps view={view} onChange={change} busy={busy} /></div>
        </div>
      )}
    </>
  );
}
