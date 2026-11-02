import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { networkApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import SeverityBadge from '../../components/SeverityBadge.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import IpForm from './IpForm.jsx';
import { SEEN_STATUS } from './networkText.js';

export default function IpDetailPage() {
  const { id } = useParams();
  const { t, units, formatNumber, formatDateTime } = useI18n();
  const { can } = useAuth();
  const navigate = useNavigate();
  const canManage = can(PERMISSIONS.manageNetwork);
  const { data, error, loading, reload } = useApi(() => networkApi.ip(id), [id]);
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  if (!data && loading) return <LoadingSpinner />;
  if (!data) return <ErrorAlert error={error} onRetry={reload} />;
  const ip = data.ip;
  return (
    <>
      <PageHeader
        title={<span className="d-inline-flex gap-2 align-items-center flex-wrap"><span className="nm-mono" dir="ltr">{ip.address}</span>
          <StatusBadge status={SEEN_STATUS[ip.seen]} label={t(`seen.${ip.seen}`)} /></span>}
        subtitle={ip.hostname ? <span dir="ltr">{ip.hostname}</span> : null}
        breadcrumbs={[{ label: t('ips.title'), to: '/ip-addresses' }, { label: ip.address }]}
        actions={<>
          <Link className="btn btn-sm btn-outline-secondary" to={`/timeline?ip=${encodeURIComponent(ip.address)}&range=7d`}>
            <i className="bi bi-clock-history me-1" aria-hidden="true" />{t('nav.timeline')}</Link>
          {!data.flowsHidden && <Link className="btn btn-sm btn-outline-secondary" to={`/traffic?ip=${encodeURIComponent(ip.address)}&range=24h`}>
            <i className="bi bi-arrow-left-right me-1" aria-hidden="true" />{t('nav.traffic')}</Link>}
          {canManage && <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEditing(true)}>{t('common.edit')}</button>}
          {canManage && <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDeleting(true)}>{t('common.delete')}</button>}
        </>} />
      <div className="row g-3">
        <div className="col-lg-5">
          <div className="card mb-3"><div className="card-body">
            <dl className="row small mb-0">
              <dt className="col-5">{t('ips.subnet')}</dt>
              <dd className="col-7">{ip.subnetCidr ? <Link to={`/ip-addresses?subnetId=${ip.subnetId}`}><span className="nm-mono" dir="ltr">{ip.subnetCidr}</span> {ip.subnetName}</Link> : t('ips.noSubnet')}</dd>
              <dt className="col-5">{t('ips.mac')}</dt><dd className="col-7 nm-mono" dir="ltr">{ip.macAddress || '—'}</dd>
              <dt className="col-5">{t('ips.allocation')}</dt><dd className="col-7">{t(`allocation.${ip.allocation}`)}</dd>
              <dt className="col-5">{t('ips.belongsTo')}</dt>
              <dd className="col-7">{ip.serverId ? <Link to={`/servers/${ip.serverId}`}>{ip.serverName}</Link> : ip.deviceName ? <Link to="/network-devices">{ip.deviceName}</Link> : '—'}</dd>
              <dt className="col-5">{t('ips.source')}</dt><dd className="col-7">{ip.sources.map((s) => t(`ipSource.${s}`)).join(t('list.separator'))}</dd>
              <dt className="col-5">{t('ips.firstSeen')}</dt><dd className="col-7">{ip.firstSeenAt ? formatDateTime(ip.firstSeenAt) : '—'}</dd>
              <dt className="col-5">{t('ips.lastSeen')}</dt>
              <dd className="col-7">{ip.lastSeenAt ? <><TimeAgo value={ip.lastSeenAt} /> <span className="text-body-secondary">({t(`ipSource.${ip.lastSeenSource}`)})</span></> : '—'}</dd>
              {ip.notes && <><dt className="col-5">{t('ips.notes')}</dt><dd className="col-7">{ip.notes}</dd></>}
            </dl>
            <p className="small text-body-secondary mt-3 mb-0">{t(`seenHelp.${ip.seen}`)}</p>
          </div></div>
          <div className="card mb-3">
            <div className="card-header"><h2 className="h6 mb-0">{t('ips.alerts')}</h2></div>
            {data.alerts.length === 0 ? <div className="card-body small text-body-secondary">{t('ips.noAlerts')}</div> : (
              <ul className="list-group list-group-flush">{data.alerts.map((a) => (
                <li key={a.id} className="list-group-item small d-flex justify-content-between gap-2">
                  <span><SeverityBadge severity={a.severity} /> <Link to={`/alerts/${a.id}`}>{a.ruleName}</Link></span>
                  <span className="text-body-secondary">{t(`alertStatus.${a.status}`)} · <TimeAgo value={a.lastTriggeredAt} /></span>
                </li>))}</ul>
            )}
          </div>
        </div>
        <div className="col-lg-7">
          <div className="card mb-3">
            <div className="card-header"><h2 className="h6 mb-0">{t('ips.flows')}</h2><small className="text-body-secondary">{t('traffic.flowLabel')}</small></div>
            <div className="card-body small">
              {data.flowsHidden ? <span className="text-body-secondary">{t('ips.trafficHidden')}</span> : data.flows.flows === 0 ? (
                <span className="text-body-secondary">{t('ips.noFlows')}</span>
              ) : (
                <>
                  <p>{t('ips.flowTotals', { sent: units.bytes(data.flows.bytesSent), recv: units.bytes(data.flows.bytesReceived), n: formatNumber(data.flows.flows) })}</p>
                  <table className="table table-sm nm-table mb-0">
                    <thead><tr><th>{t('ips.peer')}</th><th className="text-end">{t('traffic.bytes')}</th><th className="text-end">{t('traffic.flows')}</th></tr></thead>
                    <tbody>{data.flows.topPeers.map((p) => (
                      <tr key={p.address}><td className="nm-mono" dir="ltr">{p.address}</td><td className="text-end nm-num">{units.bytes(p.bytes)}</td>
                        <td className="text-end nm-num">{formatNumber(p.flows)}</td></tr>))}</tbody>
                  </table>
                </>
              )}
            </div>
          </div>
          <div className="card">
            <div className="card-header"><h2 className="h6 mb-0">{t('ips.captures')}</h2><small className="text-body-secondary">{t('ips.capturesHint')}</small></div>
            <div className="card-body small">
              {data.capturesHidden ? <span className="text-body-secondary">{t('ips.trafficHidden')}</span> : data.captures.length === 0 ? (
                <span className="text-body-secondary">{t('ips.noCaptures')}</span>
              ) : (
                <ul className="mb-0 ps-3">{data.captures.map((c) => (
                  <li key={c.captureId}><Link to={`/captures/${c.captureId}`}>{c.captureName}</Link> — {t('ips.capturePackets', { n: formatNumber(c.packets) })}
                    <span className="text-body-secondary"> · {formatDateTime(c.first)}</span></li>))}</ul>
              )}
            </div>
          </div>
        </div>
      </div>
      {editing && <IpForm ip={ip} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); reload(); }} />}
      <ConfirmDialog show={deleting} title={t('ips.deleteTitle')} confirmLabel={t('common.delete')}
        onConfirm={async () => { await networkApi.deleteIp(ip.id); navigate('/ip-addresses'); }} onCancel={() => setDeleting(false)}>
        {t('ips.deleteText')}
      </ConfirmDialog>
    </>
  );
}
