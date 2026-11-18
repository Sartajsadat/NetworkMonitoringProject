import { Fragment, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { websiteApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';

const STATE_STYLE = {
  BLOCKED: 'text-danger', ALLOWED: 'text-success', WAITING: 'text-body-secondary', OFFLINE: 'text-warning-emphasis', NOT_SET_UP: 'text-body-secondary',
};

/** "Blocked on 3 of 4 devices · waiting for 1" in plain words. */
function SiteStatus({ site }) {
  const { t, formatNumber } = useI18n();
  const s = site.summary;
  const setUp = s.total - s.notSetUp;
  if (setUp === 0) return <span className="small text-body-secondary">{t('websites.noDeviceYet')}</span>;
  return (
    <div className="small">
      <div className={s.done === setUp ? 'fw-semibold' : ''}>
        {t(site.blocked ? 'websites.doneBlocked' : 'websites.doneAllowed', { done: formatNumber(s.done), total: formatNumber(setUp) })}
      </div>
      {s.waiting > 0 && <div className="text-body-secondary"><span className="spinner-border spinner-border-sm me-1" aria-hidden="true" />
        {t('websites.waiting', { n: formatNumber(s.waiting) })}</div>}
      {s.offline > 0 && <div className="text-warning-emphasis">{t('websites.offline', { n: formatNumber(s.offline) })}</div>}
    </div>
  );
}

function Setup({ devices }) {
  const { t, formatNumber } = useI18n();
  const ready = devices.filter((d) => d.enabled);
  const [open, setOpen] = useState(ready.length === 0);
  return (
    <div className={`alert ${ready.length === 0 ? 'alert-warning' : 'alert-light border'} small`}>
      <div className="d-flex justify-content-between align-items-center gap-2 flex-wrap">
        <span><i className="bi bi-pc-display me-1" aria-hidden="true" />
          {ready.length === 0 ? t('websites.noDevices') : t('websites.devicesReady', { n: formatNumber(ready.length), total: formatNumber(devices.length) })}</span>
        <button type="button" className="btn btn-sm btn-link p-0" onClick={() => setOpen(!open)} aria-expanded={open}>
          {open ? t('websites.hideSetup') : t('websites.showSetup')}</button>
      </div>
      {open && (
        <div className="mt-2">
          <p className="mb-1">{t('websites.setupIntro')}</p>
          <div className="fw-semibold">Linux</div>
          <pre className="nm-mono bg-body-tertiary p-2 rounded mb-2" dir="ltr">sudo ./deploy/linux/enable-website-blocking.sh</pre>
          <div className="fw-semibold">Windows ({t('websites.asAdmin')})</div>
          <pre className="nm-mono bg-body-tertiary p-2 rounded mb-2" dir="ltr">.\deploy\windows\enable-website-blocking.ps1</pre>
          <p className="mb-2">{t('websites.setupNewDevice')} <Link to="/servers">{t('nav.servers')}</Link></p>
          {devices.length > 0 && (
            <table className="table table-sm mb-0"><tbody>
              {devices.map((d) => (
                <tr key={d.serverId}>
                  <td><Link to={`/servers/${d.serverId}`}>{d.name}</Link> <span className="text-body-secondary">({d.osFamily})</span></td>
                  <td>{d.enabled
                    ? <span className="text-success"><i className="bi bi-check-circle me-1" aria-hidden="true" />{t('websites.ready')}</span>
                    : <span className="text-body-secondary">{t('websites.notReady')}</span>}
                    {d.enabled && !d.online && <span className="text-warning-emphasis ms-2">{t('websites.deviceOffline')}</span>}
                    {d.message && <div className="text-body-secondary">{d.message}</div>}</td>
                  <td className="text-end text-body-secondary">{d.reportedAt ? <TimeAgo value={d.reportedAt} /> : '—'}</td>
                </tr>))}
            </tbody></table>)}
        </div>
      )}
    </div>
  );
}

export default function WebsitesPage() {
  const { t } = useI18n();
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageFirewall);
  const { data, error, loading, reload, setData } = useApi(() => websiteApi.overview(), []);
  const [website, setWebsite] = useState('');
  const [busy, setBusy] = useState(null);
  const [actionError, setActionError] = useState(null);
  const [expanded, setExpanded] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const waiting = (data?.sites || []).some((s) => s.summary.waiting > 0);
  useEffect(() => {
    if (!waiting) return undefined;
    const id = setInterval(reload, 5000);
    return () => clearInterval(id);
  }, [waiting, reload]);

  const act = async (key, fn) => {
    setBusy(key);
    setActionError(null);
    try {
      setData(await fn());
      return true;
    } catch (e) {
      setActionError(e);
      return false;
    } finally {
      setBusy(null);
    }
  };
  const add = async (e) => {
    e.preventDefault();
    if (await act('add', () => websiteApi.add(website.trim(), null))) setWebsite('');
  };

  return (
    <>
      <PageHeader title={t('websites.title')} subtitle={t('websites.subtitle')} breadcrumbs={[{ label: t('websites.title') }]} />
      {canManage && (
        <div className="card mb-3"><div className="card-body">
          <form className="d-flex gap-2 flex-wrap" onSubmit={add}>
            <label className="visually-hidden" htmlFor="ws-name">{t('websites.website')}</label>
            <input id="ws-name" className="form-control nm-ltr" dir="ltr" style={{ maxWidth: '24rem' }} required maxLength={300}
              placeholder="facebook.com" value={website} onChange={(e) => setWebsite(e.target.value)} />
            <button type="submit" className="btn btn-danger" disabled={busy === 'add' || !website.trim()}>
              <i className="bi bi-slash-circle me-1" aria-hidden="true" />{t('websites.block')}</button>
          </form>
          <div className="form-text">{t('websites.addHint')}</div>
        </div></div>
      )}
      <ErrorAlert error={error} onRetry={reload} />
      <ErrorAlert error={actionError} />
      {!data && loading && <LoadingSpinner />}
      {data && <Setup devices={data.devices} />}
      {data && data.sites.length === 0 && <div className="card"><EmptyState icon="globe" title={t('websites.none')}>{t('websites.noneHint')}</EmptyState></div>}
      {data && data.sites.length > 0 && (
        <div className="card"><div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr><th>{t('websites.website')}</th><th>{t('websites.blocking')}</th><th>{t('websites.devices')}</th><th /></tr></thead>
            <tbody>
              {data.sites.map((s) => (
                <Fragment key={s.id}>
                  <tr>
                    <td className="fw-semibold nm-ltr" dir="ltr">{s.domain}
                      <div className="small text-body-secondary fw-normal">{t('websites.alsoBlocks', { list: s.names.slice(1).join(', ') })}</div></td>
                    <td>
                      <div className="form-check form-switch mb-0 d-flex align-items-center gap-2">
                        <input className={`form-check-input ${s.blocked ? 'bg-danger border-danger' : ''}`} type="checkbox" role="switch"
                          style={{ transform: 'scale(1.4)' }}
                          aria-label={t('websites.switchLabel', { name: s.domain })} checked={s.blocked} disabled={!canManage || busy === s.id}
                          onChange={() => act(s.id, () => websiteApi.setBlocked(s.id, !s.blocked))} />
                        <span className={`small fw-semibold ms-2 ${s.blocked ? 'text-danger' : 'text-success'}`}>
                          {s.blocked ? t('websites.blocked') : t('websites.allowed')}</span>
                      </div>
                    </td>
                    <td><SiteStatus site={s} /></td>
                    <td className="text-end text-nowrap">
                      <button type="button" className="btn btn-sm btn-outline-secondary me-1" aria-expanded={expanded === s.id}
                        onClick={() => setExpanded(expanded === s.id ? null : s.id)}>{t('websites.details')}</button>
                      {canManage && <button type="button" className="btn btn-sm btn-outline-danger" aria-label={t('common.delete')}
                        onClick={() => setDeleting(s)}><i className="bi bi-trash" aria-hidden="true" /></button>}
                    </td>
                  </tr>
                  {expanded === s.id && (
                    <tr><td colSpan={4} className="bg-body-tertiary">
                      <ul className="list-unstyled small mb-0">
                        {s.devices.map((d) => (
                          <li key={d.serverId}><Link to={`/servers/${d.serverId}`}>{d.serverName}</Link>:{' '}
                            <span className={STATE_STYLE[d.state]}>{t(`websites.state.${d.state}`)}</span></li>))}
                      </ul>
                      <div className="small text-body-secondary mt-2">{t('websites.changedBy', { user: s.updatedBy })} <TimeAgo value={s.updatedAt} /></div>
                    </td></tr>)}
                </Fragment>
              ))}
            </tbody>
          </table>
        </div></div>
      )}
      <div className="small text-body-secondary mt-3"><i className="bi bi-info-circle me-1" aria-hidden="true" />{t('websites.limits')}</div>
      <ConfirmDialog show={Boolean(deleting)} title={t('websites.deleteTitle')} confirmLabel={t('common.delete')} busy={busy === 'delete'}
        onConfirm={async () => { await act('delete', () => websiteApi.remove(deleting.id)); setDeleting(null); }}
        onCancel={() => setDeleting(null)}>{t('websites.deleteText', { name: deleting?.domain || '' })}</ConfirmDialog>
    </>
  );
}
