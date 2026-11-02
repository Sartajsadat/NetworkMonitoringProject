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
import UsageBar from '../../components/UsageBar.jsx';
import { blankToNull } from './networkText.js';

const EMPTY = { cidr: '', name: '', description: '', vlanId: '', gateway: '', location: '', organization: '' };

function SubnetForm({ subnet, onSaved, onClose }) {
  const { t } = useI18n();
  const [f, setF] = useState(subnet ? { ...EMPTY, ...Object.fromEntries(Object.entries(subnet).map(([k, v]) => [k, v ?? ''])) } : EMPTY);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setF((x) => ({ ...x, [k]: e.target.value }));
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = blankToNull({ cidr: f.cidr, name: f.name, description: f.description, gateway: f.gateway, location: f.location, organization: f.organization });
    body.vlanId = f.vlanId === '' ? null : Number(f.vlanId);
    try {
      onSaved(subnet ? await networkApi.updateSubnet(subnet.id, body) : await networkApi.createSubnet(body));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  return (
    <Modal show title={subnet ? t('subnets.edit') : t('subnets.new')} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
        <button type="submit" form="subnet-form" className="btn btn-primary" disabled={busy}>{t('common.save')}</button></>)}>
      <form id="subnet-form" onSubmit={save} className="row g-3">
        <div className="col-md-6">
          <label className="form-label" htmlFor="sn-cidr">{t('subnets.cidr')}</label>
          <input id="sn-cidr" className="form-control nm-ltr" dir="ltr" required maxLength={50} value={f.cidr} onChange={set('cidr')} placeholder="10.10.1.0/24" />
          <div className="form-text">{t('subnets.cidrHelp')}</div>
        </div>
        <div className="col-md-6">
          <label className="form-label" htmlFor="sn-name">{t('subnets.name')}</label>
          <input id="sn-name" className="form-control" required maxLength={120} value={f.name} onChange={set('name')} />
        </div>
        <div className="col-md-4">
          <label className="form-label" htmlFor="sn-vlan">{t('subnets.vlan')}</label>
          <input id="sn-vlan" type="number" min={1} max={4094} className="form-control" value={f.vlanId} onChange={set('vlanId')} />
        </div>
        <div className="col-md-8">
          <label className="form-label" htmlFor="sn-gw">{t('subnets.gateway')}</label>
          <input id="sn-gw" className="form-control nm-ltr" dir="ltr" maxLength={45} value={f.gateway} onChange={set('gateway')} />
        </div>
        <div className="col-md-6">
          <label className="form-label" htmlFor="sn-loc">{t('subnets.location')}</label>
          <input id="sn-loc" className="form-control" maxLength={120} value={f.location} onChange={set('location')} />
        </div>
        <div className="col-md-6">
          <label className="form-label" htmlFor="sn-org">{t('subnets.organization')}</label>
          <input id="sn-org" className="form-control" maxLength={150} value={f.organization} onChange={set('organization')} />
        </div>
        <div className="col-12">
          <label className="form-label" htmlFor="sn-desc">{t('subnets.description')}</label>
          <input id="sn-desc" className="form-control" maxLength={500} value={f.description} onChange={set('description')} />
        </div>
        <div className="col-12"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}

export default function SubnetsPage() {
  const { t, formatNumber } = useI18n();
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageNetwork);
  const { data, error, loading, reload } = useApi(() => networkApi.subnets(), []);
  const [editing, setEditing] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);
  const remove = async () => {
    setBusy(true);
    try {
      await networkApi.deleteSubnet(deleting.id);
      setDeleting(null);
      reload();
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <PageHeader title={t('subnets.title')} subtitle={t('subnets.subtitle')} breadcrumbs={[{ label: t('subnets.title') }]}
        actions={canManage && <button type="button" className="btn btn-sm btn-primary" onClick={() => setEditing('new')}>
          <i className="bi bi-plus-lg me-1" aria-hidden="true" />{t('subnets.new')}</button>} />
      <ErrorAlert error={error} onRetry={reload} />
      {!data && loading && <LoadingSpinner />}
      {data && data.length === 0 && <div className="card"><EmptyState icon="diagram-2" title={t('subnets.none')}>{t('subnets.noneHint')}</EmptyState></div>}
      {data && data.length > 0 && (
        <div className="card"><div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr>
              <th>{t('subnets.cidr')}</th><th>{t('subnets.name')}</th><th>{t('subnets.vlan')}</th><th>{t('subnets.gateway')}</th>
              <th>{t('subnets.location')}</th><th style={{ minWidth: 180 }}>{t('subnets.usage')}</th><th>{t('subnets.seen24h')}</th>{canManage && <th />}
            </tr></thead>
            <tbody>
              {data.map((s) => (
                <tr key={s.id}>
                  <td><Link to={`/ip-addresses?subnetId=${s.id}`} className="nm-mono fw-semibold" dir="ltr">{s.cidr}</Link></td>
                  <td>{s.name}{s.organization && <div className="small text-body-secondary">{s.organization}</div>}</td>
                  <td className="nm-num">{s.vlanId ?? '—'}</td>
                  <td className="nm-mono small" dir="ltr">{s.gateway || '—'}</td>
                  <td className="small">{s.location || '—'}</td>
                  <td className="small">
                    {s.usedPercent != null ? <UsageBar value={s.usedPercent} label={t('subnets.known', { n: formatNumber(s.knownAddresses), total: formatNumber(Number(s.usable)) })} />
                      : <>{t('subnets.knownV6', { n: formatNumber(s.knownAddresses) })}</>}
                  </td>
                  <td className="nm-num">{formatNumber(s.seenLast24h)}</td>
                  {canManage && (
                    <td className="text-nowrap">
                      <button type="button" className="btn btn-sm btn-outline-primary me-1" onClick={() => setEditing(s)} aria-label={t('common.edit')}><i className="bi bi-pencil" aria-hidden="true" /></button>
                      <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDeleting(s)} aria-label={t('common.delete')}><i className="bi bi-trash" aria-hidden="true" /></button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card-footer small text-body-secondary">{t('subnets.footer')}</div></div>
      )}
      {editing && <SubnetForm subnet={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload(); }} />}
      <ConfirmDialog show={Boolean(deleting)} title={t('subnets.deleteTitle')} confirmLabel={t('common.delete')} busy={busy}
        onConfirm={remove} onCancel={() => setDeleting(null)}>{t('subnets.deleteText', { cidr: deleting?.cidr || '' })}</ConfirmDialog>
    </>
  );
}
