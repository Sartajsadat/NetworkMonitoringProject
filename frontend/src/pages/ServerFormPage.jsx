import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { groupApi, serverApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import { ENV_LABELS, GROUP_TYPE_LABELS, OS_LABELS } from './labels.js';

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

export default function ServerFormPage() {
  const { id } = useParams();
  const editing = Boolean(id);
  const navigate = useNavigate();
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
      setError(`Not a valid IP address: ${invalidIps.map((i) => i.address).join(', ')}`);
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

  return (
    <>
      <PageHeader
        title={editing ? `Edit ${existing?.name || 'server'}` : 'Register server'}
        breadcrumbs={[{ label: 'Server inventory', to: '/servers' }, { label: editing ? 'Edit' : 'Register' }]}
      />
      <form className="card card-body" onSubmit={submit} style={{ maxWidth: 960 }}>
        <ErrorAlert error={error} />
        <div className="row g-3">
          <div className="col-md-6">
            <label htmlFor="s-name" className="form-label">Server name *</label>
            <input id="s-name" className="form-control" value={form.name} onChange={set('name')} required maxLength={120} />
          </div>
          <div className="col-md-6">
            <label htmlFor="s-host" className="form-label">Hostname *</label>
            <input id="s-host" className="form-control font-monospace" value={form.hostname} onChange={set('hostname')} required maxLength={253} placeholder="app01.mot.local" />
          </div>
          <div className="col-md-3">
            <label htmlFor="s-os" className="form-label">Operating system *</label>
            <select id="s-os" className="form-select" value={form.osFamily} onChange={set('osFamily')}>
              {Object.entries(OS_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </div>
          <div className="col-md-3">
            <label htmlFor="s-osv" className="form-label">OS version</label>
            <input id="s-osv" className="form-control" value={form.osVersion} onChange={set('osVersion')} placeholder="Ubuntu 24.04 LTS" maxLength={120} />
          </div>
          <div className="col-md-3">
            <label htmlFor="s-env" className="form-label">Environment *</label>
            <select id="s-env" className="form-select" value={form.environment} onChange={set('environment')}>
              {Object.entries(ENV_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </div>
          <div className="col-md-3">
            <label htmlFor="s-status" className="form-label">Monitoring status</label>
            <select id="s-status" className="form-select" value={form.monitoringStatus} onChange={set('monitoringStatus')}>
              {statusOptions.map((s) => <option key={s} value={s}>{s.replace('_', ' ').toLowerCase()}</option>)}
            </select>
            <div className="form-text">"Active" is set automatically by the agent.</div>
          </div>
          <div className="col-md-6">
            <label htmlFor="s-org" className="form-label">Owning organization / department *</label>
            <input id="s-org" className="form-control" value={form.organization} onChange={set('organization')} required maxLength={150} />
          </div>
          <div className="col-md-6">
            <label htmlFor="s-loc" className="form-label">Location / data centre</label>
            <input id="s-loc" className="form-control" value={form.location} onChange={set('location')} maxLength={120} />
          </div>
          <div className="col-12">
            <label htmlFor="s-purpose" className="form-label">Purpose</label>
            <input id="s-purpose" className="form-control" value={form.purpose} onChange={set('purpose')} maxLength={500} />
          </div>

          <div className="col-12">
            <div className="d-flex justify-content-between align-items-center">
              <span className="form-label mb-0">IP addresses</span>
              <button type="button" className="btn btn-sm btn-outline-secondary"
                onClick={() => setForm({ ...form, ipAddresses: [...form.ipAddresses, { address: '', label: '' }] })}>
                <i className="bi bi-plus" aria-hidden="true" /> Add IP
              </button>
            </div>
            {form.ipAddresses.length === 0 && <div className="small text-body-secondary mt-1">No IP addresses added.</div>}
            {form.ipAddresses.map((ip, i) => {
              const bad = ip.address && !looksLikeIp(ip.address);
              return (
                <div className="row g-2 mt-1" key={i}>
                  <div className="col-md-5">
                    <input aria-label={`IP address ${i + 1}`} className={`form-control form-control-sm font-monospace ${bad ? 'is-invalid' : ''}`}
                      value={ip.address} onChange={setIp(i, 'address')} placeholder="10.0.0.5 or 2001:db8::5" />
                    {bad && <div className="invalid-feedback">Enter an IPv4 or IPv6 address (no hostnames or CIDR).</div>}
                  </div>
                  <div className="col-md-5">
                    <input aria-label={`Label for IP ${i + 1}`} className="form-control form-control-sm" value={ip.label}
                      onChange={setIp(i, 'label')} placeholder="Label (e.g. management)" maxLength={60} />
                  </div>
                  <div className="col-md-2">
                    <button type="button" className="btn btn-sm btn-outline-danger" aria-label={`Remove IP ${i + 1}`}
                      onClick={() => setForm({ ...form, ipAddresses: form.ipAddresses.filter((_, j) => j !== i) })}>
                      <i className="bi bi-x-lg" aria-hidden="true" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>

          <div className="col-12">
            <span className="form-label d-block">Server groups</span>
            {(groups || []).length === 0 && <div className="small text-body-secondary">No groups defined yet (see Server groups).</div>}
            <div className="d-flex flex-wrap gap-3">
              {(groups || []).map((g) => (
                <div className="form-check" key={g.id}>
                  <input className="form-check-input" type="checkbox" id={`g-${g.id}`} checked={form.groupIds.includes(g.id)} onChange={() => toggleGroup(g.id)} />
                  <label className="form-check-label" htmlFor={`g-${g.id}`}>{g.name} <small className="text-body-secondary">({GROUP_TYPE_LABELS[g.groupType]})</small></label>
                </div>
              ))}
            </div>
          </div>

          <div className="col-12">
            <label htmlFor="s-notes" className="form-label">Notes</label>
            <textarea id="s-notes" className="form-control" rows={3} value={form.notes} onChange={set('notes')} maxLength={2000} />
          </div>
        </div>
        <div className="mt-3 d-flex gap-2">
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy && <span className="spinner-border spinner-border-sm me-1" aria-hidden="true" />}
            {editing ? 'Save changes' : 'Register server'}
          </button>
          <button type="button" className="btn btn-outline-secondary" onClick={() => navigate(-1)}>Cancel</button>
        </div>
      </form>
    </>
  );
}
