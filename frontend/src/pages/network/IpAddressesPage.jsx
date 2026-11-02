import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { networkApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Modal from '../../components/Modal.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Pagination from '../../components/Pagination.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import IpForm from './IpForm.jsx';
import { SEEN_STATUS, SEEN_VALUES, SOURCES } from './networkText.js';

function ImportDialog({ onDone, onClose }) {
  const { t, formatNumber } = useI18n();
  const [csv, setCsv] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      setResult(await networkApi.importIps(csv));
      onDone();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal show size="lg" title={t('ips.import')} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.close')}</button>
        <button type="button" className="btn btn-primary" onClick={run} disabled={busy || !csv.trim()}>{t('ips.importRun')}</button></>)}>
      <p className="small">{t('ips.importHelp')}</p>
      <input type="file" accept=".csv,.txt" className="form-control form-control-sm mb-2" aria-label={t('ips.importFile')}
        onChange={(e) => e.target.files[0]?.text().then(setCsv)} />
      <textarea className="form-control nm-mono small" dir="ltr" rows={8} value={csv} onChange={(e) => setCsv(e.target.value)}
        placeholder={'address,mac,hostname,allocation\n10.10.1.20,aa:bb:cc:dd:ee:01,printer-1,RESERVED'} aria-label="CSV" />
      <ErrorAlert error={error} />
      {result && (
        <div className="alert alert-success small mt-2 mb-0">
          {t('ips.importResult', { n: formatNumber(result.imported), u: formatNumber(result.updated), e: formatNumber(result.errors.length) })}
          {result.errors.length > 0 && <ul className="mb-0 nm-ltr" dir="ltr">{result.errors.map((x) => <li key={x}>{x}</li>)}</ul>}
        </div>
      )}
    </Modal>
  );
}

export default function IpAddressesPage() {
  const { t } = useI18n();
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageNetwork);
  const [params, setParams] = useSearchParams();
  const f = { q: params.get('q') || '', subnetId: params.get('subnetId') || '', source: params.get('source') || '',
    seen: params.get('seen') || '', noSubnet: params.get('noSubnet') === 'true', page: Number(params.get('page') || 0) };
  const [q, setQ] = useState(f.q);
  useEffect(() => setQ(f.q), [f.q]);
  const update = (c) => setParams(Object.fromEntries(Object.entries({ ...f, page: 0, ...c }).filter(([, v]) => v && v !== 0)), { replace: true });
  const subnets = useApi(() => networkApi.subnets(), []);
  const list = useApi(() => networkApi.ips({ q: f.q || undefined, subnetId: f.subnetId || undefined, source: f.source || undefined,
    seen: f.seen || undefined, noSubnet: f.noSubnet || undefined, page: f.page, size: 50 }), [params.toString()]);
  const [adding, setAdding] = useState(false);
  const [importing, setImporting] = useState(false);
  return (
    <>
      <PageHeader title={t('ips.title')} subtitle={t('ips.subtitle')} breadcrumbs={[{ label: t('ips.title') }]}
        actions={canManage && <>
          <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setImporting(true)}><i className="bi bi-upload me-1" aria-hidden="true" />{t('ips.import')}</button>
          <button type="button" className="btn btn-sm btn-primary" onClick={() => setAdding(true)}><i className="bi bi-plus-lg me-1" aria-hidden="true" />{t('ips.new')}</button>
        </>} />
      <div className="alert alert-info small"><i className="bi bi-info-circle me-1" aria-hidden="true" />{t('ips.passiveNote')}</div>
      <div className="card mb-3"><div className="card-body">
        <form className="row g-2 align-items-end" onSubmit={(e) => { e.preventDefault(); update({ q: q.trim() }); }}>
          <div className="col-md-4"><label className="form-label small" htmlFor="ip-q">{t('common.search')}</label>
            <input id="ip-q" className="form-control form-control-sm" maxLength={100} value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('ips.searchHint')} /></div>
          <div className="col-md-3"><label className="form-label small" htmlFor="ip-sn">{t('ips.subnet')}</label>
            <select id="ip-sn" className="form-select form-select-sm" value={f.noSubnet ? '-' : f.subnetId}
              onChange={(e) => update(e.target.value === '-' ? { subnetId: '', noSubnet: true } : { subnetId: e.target.value, noSubnet: false })}>
              <option value="">{t('common.all')}</option>
              <option value="-">{t('ips.noSubnet')}</option>
              {(subnets.data || []).map((s) => <option key={s.id} value={s.id}>{s.cidr} — {s.name}</option>)}
            </select></div>
          <div className="col-md-2"><label className="form-label small" htmlFor="ip-src">{t('ips.source')}</label>
            <select id="ip-src" className="form-select form-select-sm" value={f.source} onChange={(e) => update({ source: e.target.value })}>
              <option value="">{t('common.all')}</option>
              {SOURCES.map((s) => <option key={s} value={s}>{t(`ipSource.${s}`)}</option>)}</select></div>
          <div className="col-md-2"><label className="form-label small" htmlFor="ip-seen">{t('ips.seen')}</label>
            <select id="ip-seen" className="form-select form-select-sm" value={f.seen} onChange={(e) => update({ seen: e.target.value })}>
              <option value="">{t('common.all')}</option>
              {SEEN_VALUES.map((s) => <option key={s} value={s}>{t(`seen.${s}`)}</option>)}</select></div>
          <div className="col-md-1"><button type="submit" className="btn btn-sm btn-outline-primary w-100">{t('common.search')}</button></div>
        </form>
      </div></div>
      <ErrorAlert error={list.error} onRetry={list.reload} />
      {!list.data && list.loading && <LoadingSpinner />}
      {list.data && list.data.totalElements === 0 && <div className="card"><EmptyState icon="geo" title={t('ips.none')}>{t('ips.noneHint')}</EmptyState></div>}
      {list.data && list.data.totalElements > 0 && (
        <div className="card">
          <div className="table-responsive">
            <table className="table nm-table mb-0 align-middle">
              <thead><tr>
                <th>{t('ips.address')}</th><th>{t('ips.subnet')}</th><th>{t('ips.hostname')}</th><th>{t('ips.mac')}</th>
                <th>{t('ips.belongsTo')}</th><th>{t('ips.seen')}</th><th>{t('ips.lastSeen')}</th><th>{t('ips.source')}</th>
              </tr></thead>
              <tbody>
                {list.data.content.map((ip) => (
                  <tr key={ip.id}>
                    <td><Link to={`/ip-addresses/${ip.id}`} className="nm-mono fw-semibold" dir="ltr">{ip.address}</Link>
                      {ip.allocation !== 'UNKNOWN' && <div className="small text-body-secondary">{t(`allocation.${ip.allocation}`)}</div>}</td>
                    <td className="small">{ip.subnetCidr ? <span className="nm-mono" dir="ltr">{ip.subnetCidr}</span> : <span className="text-body-secondary">{t('ips.noSubnet')}</span>}</td>
                    <td className="small nm-ltr" dir="ltr">{ip.hostname || '—'}</td>
                    <td className="small nm-mono" dir="ltr">{ip.macAddress || '—'}</td>
                    <td className="small">{ip.serverId ? <Link to={`/servers/${ip.serverId}`}>{ip.serverName}</Link> : ip.deviceName || '—'}</td>
                    <td><StatusBadge status={SEEN_STATUS[ip.seen]} label={t(`seen.${ip.seen}`)} /></td>
                    <td className="small">{ip.lastSeenAt ? <TimeAgo value={ip.lastSeenAt} /> : '—'}</td>
                    <td className="small">{ip.sources.map((s) => t(`ipSource.${s}`)).join(t('list.separator'))}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="card-footer"><Pagination data={list.data} onPage={(page) => update({ page })} /></div>
        </div>
      )}
      {adding && <IpForm onClose={() => setAdding(false)} onSaved={() => { setAdding(false); list.reload(); }} />}
      {importing && <ImportDialog onClose={() => setImporting(false)} onDone={list.reload} />}
    </>
  );
}
