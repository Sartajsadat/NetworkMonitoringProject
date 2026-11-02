import { useState } from 'react';
import { Link } from 'react-router-dom';
import { networkApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Modal from '../../components/Modal.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import { DEVICE_TYPES, blankToNull } from './networkText.js';

const EMPTY = { name: '', deviceType: 'ROUTER', vendor: '', model: '', managementIp: '', location: '', description: '', flowExport: false, exporterIp: '' };

function DeviceForm({ device, onSaved, onClose }) {
  const { t } = useI18n();
  const [f, setF] = useState(device ? { ...EMPTY, ...Object.fromEntries(Object.entries(device).map(([k, v]) => [k, v ?? ''])), flowExport: device.flowExport } : EMPTY);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setF((x) => ({ ...x, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value }));
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = { ...blankToNull({ name: f.name, deviceType: f.deviceType, vendor: f.vendor, model: f.model, managementIp: f.managementIp,
      location: f.location, description: f.description, exporterIp: f.exporterIp }), flowExport: f.flowExport };
    try {
      onSaved(device ? await networkApi.updateDevice(device.id, body) : await networkApi.createDevice(body));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  return (
    <Modal show size="lg" title={device ? t('devices.edit') : t('devices.new')} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
        <button type="submit" form="device-form" className="btn btn-primary" disabled={busy}>{t('common.save')}</button></>)}>
      <form id="device-form" onSubmit={save} className="row g-3">
        <div className="col-md-6"><label className="form-label" htmlFor="dv-name">{t('devices.name')}</label>
          <input id="dv-name" className="form-control" required maxLength={120} value={f.name} onChange={set('name')} /></div>
        <div className="col-md-6"><label className="form-label" htmlFor="dv-type">{t('devices.type')}</label>
          <select id="dv-type" className="form-select" value={f.deviceType} onChange={set('deviceType')}>
            {DEVICE_TYPES.map((x) => <option key={x} value={x}>{t(`deviceType.${x}`)}</option>)}</select></div>
        <div className="col-md-4"><label className="form-label" htmlFor="dv-vendor">{t('devices.vendor')}</label>
          <input id="dv-vendor" className="form-control" maxLength={80} value={f.vendor} onChange={set('vendor')} /></div>
        <div className="col-md-4"><label className="form-label" htmlFor="dv-model">{t('devices.model')}</label>
          <input id="dv-model" className="form-control" maxLength={80} value={f.model} onChange={set('model')} /></div>
        <div className="col-md-4"><label className="form-label" htmlFor="dv-mgmt">{t('devices.managementIp')}</label>
          <input id="dv-mgmt" className="form-control nm-ltr" dir="ltr" maxLength={45} value={f.managementIp} onChange={set('managementIp')} /></div>
        <div className="col-md-6"><label className="form-label" htmlFor="dv-loc">{t('subnets.location')}</label>
          <input id="dv-loc" className="form-control" maxLength={120} value={f.location} onChange={set('location')} /></div>
        <div className="col-md-6"><label className="form-label" htmlFor="dv-desc">{t('subnets.description')}</label>
          <input id="dv-desc" className="form-control" maxLength={500} value={f.description} onChange={set('description')} /></div>
        <div className="col-12"><div className="card"><div className="card-body">
          <div className="form-check mb-2">
            <input id="dv-flow" type="checkbox" className="form-check-input" checked={f.flowExport} onChange={set('flowExport')} />
            <label className="form-check-label fw-semibold" htmlFor="dv-flow">{t('devices.flowExport')}</label>
          </div>
          <label className="form-label small" htmlFor="dv-exp">{t('devices.exporterIp')}</label>
          <input id="dv-exp" className="form-control nm-ltr" dir="ltr" maxLength={45} value={f.exporterIp} onChange={set('exporterIp')}
            disabled={!f.flowExport} placeholder={f.managementIp || '10.255.0.1'} />
          <div className="form-text">{t('devices.exporterHelp')}</div>
        </div></div></div>
        <div className="col-12"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}

export default function DevicesPage() {
  const { t, formatNumber } = useI18n();
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageNetwork);
  const { data, error, loading, reload } = useApi(() => networkApi.devices(), []);
  const collector = useApi(() => (can(PERMISSIONS.viewTraffic) ? networkApi.collector() : Promise.resolve(null)), []);
  const [editing, setEditing] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);
  const remove = async () => {
    setBusy(true);
    try { await networkApi.deleteDevice(deleting.id); setDeleting(null); reload(); } finally { setBusy(false); }
  };
  const c = collector.data;
  return (
    <>
      <PageHeader title={t('devices.title')} subtitle={t('devices.subtitle')} breadcrumbs={[{ label: t('devices.title') }]}
        actions={canManage && <button type="button" className="btn btn-sm btn-primary" onClick={() => setEditing('new')}>
          <i className="bi bi-plus-lg me-1" aria-hidden="true" />{t('devices.new')}</button>} />
      {c && (
        <div className={`alert ${c.listening ? 'alert-success' : 'alert-secondary'} small`}>
          <i className={`bi ${c.listening ? 'bi-broadcast' : 'bi-pause-circle'} me-1`} aria-hidden="true" />
          {c.listening ? t('devices.collectorOn', { nf: c.netflowPort, ipfix: c.ipfixPort }) : t('devices.collectorOff')}
          {c.rejectedPackets > 0 && <> {t('devices.rejected', { n: formatNumber(c.rejectedPackets) })}</>}
        </div>
      )}
      <ErrorAlert error={error} onRetry={reload} />
      {!data && loading && <LoadingSpinner />}
      {data && data.length === 0 && <div className="card"><EmptyState icon="router" title={t('devices.none')}>{t('devices.noneHint')}</EmptyState></div>}
      {data && data.length > 0 && (
        <div className="card"><div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr>
              <th>{t('devices.name')}</th><th>{t('devices.type')}</th><th>{t('devices.managementIp')}</th><th>{t('devices.flows')}</th>
              <th>{t('devices.lastFlow')}</th>{canManage && <th />}
            </tr></thead>
            <tbody>
              {data.map((d) => (
                <tr key={d.id}>
                  <td className="fw-semibold">{d.name}<div className="small text-body-secondary">{[d.vendor, d.model, d.location].filter(Boolean).join(' · ')}</div></td>
                  <td>{t(`deviceType.${d.deviceType}`)}</td>
                  <td className="nm-mono small" dir="ltr">{d.managementIp || '—'}</td>
                  <td className="small">
                    {d.flowExport ? (
                      <>
                        <StatusBadge status={d.lastFlowAt ? 'LIVE' : 'NOT_COLLECTED'} label={d.lastFlowAt ? t('devices.exporting') : t('devices.waiting')} />
                        <div className="text-body-secondary">
                          <span className="nm-mono" dir="ltr">{d.exporterIp}</span>
                          {d.lastFlowVersion && <> · {d.lastFlowVersion === 10 ? 'IPFIX' : `NetFlow v${d.lastFlowVersion}`}</>}
                          {d.samplingInterval > 1 && <> · {t('traffic.sampledOne', { n: formatNumber(d.samplingInterval) })}</>}
                        </div>
                        <div className="text-body-secondary">{t('devices.counts', { n: formatNumber(d.flowsReceived), d: formatNumber(d.flowsDropped) })}</div>
                      </>
                    ) : <span className="text-body-secondary">{t('devices.noExport')}</span>}
                  </td>
                  <td className="small">{d.flowExport ? <TimeAgo value={d.lastFlowAt} /> : '—'}
                    {d.flowExport && d.lastFlowAt && <div><Link to={`/traffic?exporterId=${d.id}`}>{t('devices.viewTraffic')}</Link></div>}</td>
                  {canManage && (
                    <td className="text-nowrap">
                      <button type="button" className="btn btn-sm btn-outline-primary me-1" onClick={() => setEditing(d)} aria-label={t('common.edit')}><i className="bi bi-pencil" aria-hidden="true" /></button>
                      <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDeleting(d)} aria-label={t('common.delete')}><i className="bi bi-trash" aria-hidden="true" /></button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div></div>
      )}
      {editing && <DeviceForm device={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload(); }} />}
      <ConfirmDialog show={Boolean(deleting)} title={t('devices.deleteTitle')} confirmLabel={t('common.delete')} busy={busy}
        onConfirm={remove} onCancel={() => setDeleting(null)}>{t('devices.deleteText', { name: deleting?.name || '' })}</ConfirmDialog>
    </>
  );
}
