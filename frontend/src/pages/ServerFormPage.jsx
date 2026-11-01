import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { groupApi, serverApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useI18n } from '../i18n/I18nContext.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import { ENV_CODES, OS_CODES } from './labels.js';

const EMPTY = {
  name: '', hostname: '', osFamily: 'LINUX', osVersion: '', environment: 'DEVELOPMENT', organization: '',
  purpose: '', location: '', monitoringStatus: 'PENDING_AGENT', notes: '', ipAddresses: [], groupIds: [],
};

// Light client-side check for immediate feedback; the backend performs the authoritative validation.
const IPV4 = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;
export function looksLikeIp(v) {
  const s = v.trim();
  return IPV4.test(s) || (s.includes(':') && /^[0-9a-fA-F:.]+$/.test(s));
}

function Req() {
  const { t } = useI18n();
  return <span className="text-danger" title={t('serverForm.required')}> *</span>;
}

export default function ServerFormPage() {
  const { id } = useParams();
  const editing = Boolean(id);
  const navigate = useNavigate();
  const { t, formatNumber } = useI18n();
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const { data: groups } = useApi(() => groupApi.list(), []);
  const { data: existing, loading, error: loadError } = useApi(() => (editing ? serverApi.get(id) : Promise.resolve(null)), [id]);

  useEffect(() => {
    if (existing) {
      setForm({
        ...EMPTY,
        ...Object.fromEntries(Object.entries(existing).map(([k, v]) => [k, v ?? ''])),
        ipAddresses: existing.ipAddresses.map((i) => ({ address: i.address, label: i.label || '' })),
        groupIds: existing.groups.map((g) => g.id),
      });
    }
  }, [existing]);

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const setIp = (i, k) => (e) => {
    const ips = [...form.ipAddresses];
    ips[i] = { ...ips[i], [k]: e.target.value };
    setForm({ ...form, ipAddresses: ips });
  };
  const toggleGroup = (gid) => setForm({
    ...form,
    groupIds: form.groupIds.includes(gid) ? form.groupIds.filter((g) => g !== gid) : [...form.groupIds, gid],
  });

  const invalidIps = form.ipAddresses.filter((i) => i.address && !looksLikeIp(i.address));

  const submit = async (e) => {
    e.preventDefault();
    if (invalidIps.length) {
      setError(t('serverForm.notValidIps', { list: invalidIps.map((i) => i.address).join(', ') }));
      return;
    }
    setBusy(true);
    setError(null);
    const body = {
      name: form.name, hostname: form.hostname, osFamily: form.osFamily, osVersion: form.osVersion || null,
      environment: form.environment, organization: form.organization, purpose: form.purpose || null,
      location: form.location || null, monitoringStatus: form.monitoringStatus, notes: form.notes || null,
      ipAddresses: form.ipAddresses.filter((i) => i.address.trim()).map((i) => ({ address: i.address.trim(), label: i.label || null })),
      groupIds: form.groupIds,
    };
    try {
      const saved = editing ? await serverApi.update(id, body) : await serverApi.create(body);
      navigate(`/servers/${saved.id}`);
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  if (editing && loading) return <LoadingSpinner />;
  if (loadError) return <ErrorAlert error={loadError} />;

  const statusOptions = ['PENDING_AGENT', 'PAUSED', 'DECOMMISSIONED'];
  if (existing?.monitoringStatus === 'ACTIVE') statusOptions.unshift('ACTIVE');
  const title = editing ? t('serverForm.titleEdit', { name: existing?.name || '' }) : t('serverForm.titleNew');

  return (
    <>
      <PageHeader title={title}
        breadcrumbs={[{ label: t('servers.title'), to: '/servers' }, { label: editing ? t('serverForm.crumbEdit') : t('serverForm.crumbNew') }]} />
      <form className="card card-body" onSubmit={submit} style={{ maxWidth: 980 }}>
        <ErrorAlert error={error} />
        <div className="row g-3">
          <div className="col-md-6">
            <label htmlFor="s-name" className="form-label">{t('serverForm.name')}<Req /></label>
            <input id="s-name" className="form-control" value={form.name} onChange={set('name')} required maxLength={120} />
          </div>
          <div className="col-md-6">
            <label htmlFor="s-host" className="form-label">{t('serverForm.hostname')}<Req /></label>
            <input id="s-host" className="form-control nm-mono nm-ltr" dir="ltr" value={form.hostname} onChange={set('hostname')} required maxLength={253} placeholder="app01.mot.local" />
          </div>
          <div className="col-md-3">
            <label htmlFor="s-os" className="form-label">{t('serverForm.os')}<Req /></label>
            <select id="s-os" className="form-select" value={form.osFamily} onChange={set('osFamily')}>
              {OS_CODES.map((k) => <option key={k} value={k}>{t(`os.${k}`)}</option>)}
            </select>
          </div>
          <div className="col-md-3">
            <label htmlFor="s-osv" className="form-label">{t('serverForm.osVersion')}</label>
            <input id="s-osv" className="form-control nm-ltr" dir="ltr" value={form.osVersion} onChange={set('osVersion')} placeholder="Ubuntu 24.04 LTS" maxLength={120} />
          </div>
          <div className="col-md-3">
            <label htmlFor="s-env" className="form-label">{t('serverForm.environment')}<Req /></label>
            <select id="s-env" className="form-select" value={form.environment} onChange={set('environment')}>
              {ENV_CODES.map((k) => <option key={k} value={k}>{t(`env.${k}`)}</option>)}
            </select>
          </div>
          <div className="col-md-3">
            <label htmlFor="s-status" className="form-label">{t('serverForm.status')}</label>
            <select id="s-status" className="form-select" value={form.monitoringStatus} onChange={set('monitoringStatus')}>
              {statusOptions.map((s) => <option key={s} value={s}>{t(`status.${s}`)}</option>)}
            </select>
            <div className="form-text">{t('serverForm.statusHint')}</div>
          </div>
          <div className="col-md-6">
            <label htmlFor="s-org" className="form-label">{t('serverForm.organization')}<Req /></label>
            <input id="s-org" className="form-control" value={form.organization} onChange={set('organization')} required maxLength={150} />
          </div>
          <div className="col-md-6">
            <label htmlFor="s-loc" className="form-label">{t('serverForm.location')}</label>
            <input id="s-loc" className="form-control" value={form.location} onChange={set('location')} maxLength={120} />
          </div>
          <div className="col-12">
            <label htmlFor="s-purpose" className="form-label">{t('serverForm.purpose')}</label>
            <input id="s-purpose" className="form-control" value={form.purpose} onChange={set('purpose')} maxLength={500} />
          </div>

          <div className="col-12">
            <div className="d-flex justify-content-between align-items-center">
              <span className="form-label mb-0">{t('serverForm.ips')}</span>
              <button type="button" className="btn btn-sm btn-outline-secondary"
                onClick={() => setForm({ ...form, ipAddresses: [...form.ipAddresses, { address: '', label: '' }] })}>
                <i className="bi bi-plus" aria-hidden="true" /> {t('serverForm.addIp')}
              </button>
            </div>
            {form.ipAddresses.length === 0 && <div className="small text-body-secondary mt-1">{t('serverForm.noIps')}</div>}
            {form.ipAddresses.map((ip, i) => {
              const bad = ip.address && !looksLikeIp(ip.address);
              const n = formatNumber(i + 1);
              return (
                <div className="row g-2 mt-1" key={i}>
                  <div className="col-md-5">
                    <input aria-label={t('serverForm.ipAria', { n })} dir="ltr"
                      className={`form-control form-control-sm nm-mono nm-ltr ${bad ? 'is-invalid' : ''}`}
                      value={ip.address} onChange={setIp(i, 'address')} placeholder={t('serverForm.ipPlaceholder')} />
                    {bad && <div className="invalid-feedback">{t('serverForm.invalidIp')}</div>}
                  </div>
                  <div className="col-md-5">
                    <input aria-label={t('serverForm.labelAria', { n })} className="form-control form-control-sm" value={ip.label}
                      onChange={setIp(i, 'label')} placeholder={t('serverForm.labelPlaceholder')} maxLength={60} />
                  </div>
                  <div className="col-md-2">
                    <button type="button" className="btn btn-sm btn-outline-danger" aria-label={t('serverForm.removeIp', { n })}
                      onClick={() => setForm({ ...form, ipAddresses: form.ipAddresses.filter((_, j) => j !== i) })}>
                      <i className="bi bi-x-lg" aria-hidden="true" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>

          <div className="col-12">
            <span className="form-label d-block">{t('serverForm.groups')}</span>
            {(groups || []).length === 0 && <div className="small text-body-secondary">{t('serverForm.noGroups')}</div>}
            <div className="d-flex flex-wrap gap-3">
              {(groups || []).map((g) => (
                <div className="form-check" key={g.id}>
                  <input className="form-check-input" type="checkbox" id={`g-${g.id}`} checked={form.groupIds.includes(g.id)} onChange={() => toggleGroup(g.id)} />
                  <label className="form-check-label" htmlFor={`g-${g.id}`}>{g.name} <small className="text-body-secondary">({t(`groupType.${g.groupType}`)})</small></label>
                </div>
              ))}
            </div>
          </div>

          <div className="col-12">
            <label htmlFor="s-notes" className="form-label">{t('serverForm.notes')}</label>
            <textarea id="s-notes" className="form-control" rows={3} value={form.notes} onChange={set('notes')} maxLength={2000} />
          </div>
        </div>
        <div className="mt-3 d-flex gap-2">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy && <span className="spinner-border spinner-border-sm me-1" aria-hidden="true" />}
            {editing ? t('common.saveChanges') : t('serverForm.titleNew')}
          </button>
          <button type="button" className="btn btn-outline-secondary" onClick={() => navigate(-1)}>{t('common.cancel')}</button>
        </div>
      </form>
    </>
  );
}
