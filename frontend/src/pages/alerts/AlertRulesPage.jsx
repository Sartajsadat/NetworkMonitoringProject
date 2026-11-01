import { useState } from 'react';
import { alertApi, serverApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import Modal from '../../components/Modal.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import SeverityBadge from '../../components/SeverityBadge.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import { SEVERITIES, formatAlertValue, windowText } from '../events/eventText.js';

/** The form edits thresholds in friendly units; the API stores base units. */
const INPUT_SCALE = { BITS_PER_SECOND: 1e6 }; // Mb/s in the form

function RuleForm({ rule, types, servers, onSaved, onClose }) {
  const { t } = useI18n();
  const first = types[0];
  const [f, setF] = useState(() => {
    const type = rule?.ruleType || first.type;
    const unit = types.find((x) => x.type === type)?.unit;
    return {
      name: rule?.name || '', description: rule?.description || '', ruleType: type, severity: rule?.severity || 'WARNING',
      threshold: rule?.threshold == null ? '' : String(rule.threshold / (INPUT_SCALE[unit] || 1)),
      windowMinutes: rule ? String(rule.windowSeconds / 60) : '5', cooldownMinutes: rule ? String(rule.cooldownSeconds / 60) : '15',
      serverId: rule?.serverId || '', resourceFilter: rule?.resourceFilter || '', enabled: rule ? rule.enabled : true,
    };
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const info = types.find((x) => x.type === f.ruleType);
  const set = (k) => (e) => setF((x) => ({ ...x, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value }));
  const scale = INPUT_SCALE[info.unit] || 1;
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body = {
      name: f.name, description: f.description, ruleType: f.ruleType, severity: f.severity,
      threshold: info.needsThreshold ? Number(f.threshold) * scale : null,
      windowSeconds: info.usesWindow ? Math.round(Number(f.windowMinutes) * 60) : 0,
      cooldownSeconds: Math.round(Number(f.cooldownMinutes) * 60),
      serverId: info.serverScoped && f.serverId ? f.serverId : null,
      resourceFilter: info.resourceKind ? f.resourceFilter : null, enabled: f.enabled,
    };
    try {
      onSaved(rule ? await alertApi.updateRule(rule.id, body) : await alertApi.createRule(body));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  return (
    <Modal show title={rule ? t('rules.edit') : t('rules.new')} onClose={() => !busy && onClose()} size="lg"
      footer={(
        <>
          <button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
          <button type="submit" form="rule-form" className="btn btn-primary" disabled={busy}>{t('common.save')}</button>
        </>
      )}>
      <form id="rule-form" onSubmit={save} className="row g-3">
        <div className="col-md-6">
          <label className="form-label" htmlFor="r-type">{t('rules.type')}</label>
          <select id="r-type" className="form-select" value={f.ruleType} disabled={Boolean(rule)} onChange={set('ruleType')}>
            {types.map((x) => <option key={x.type} value={x.type}>{t(`alertType.${x.type}`)}</option>)}
          </select>
          <div className="form-text">{t(`alertTypeHelp.${f.ruleType}`)}</div>
        </div>
        <div className="col-md-6">
          <label className="form-label" htmlFor="r-name">{t('rules.name')}</label>
          <input id="r-name" className="form-control" required maxLength={120} value={f.name} onChange={set('name')} />
        </div>
        <div className="col-md-4">
          <label className="form-label" htmlFor="r-sev">{t('alerts.severity')}</label>
          <select id="r-sev" className="form-select" value={f.severity} onChange={set('severity')}>
            {SEVERITIES.map((s) => <option key={s} value={s}>{t(`severity.${s}`)}</option>)}
          </select>
        </div>
        {info.needsThreshold && (
          <div className="col-md-4">
            <label className="form-label" htmlFor="r-th">{t('alert.threshold')}</label>
            <div className="input-group">
              <input id="r-th" type="number" step="any" className="form-control" required value={f.threshold} onChange={set('threshold')}
                min={info.minThreshold / scale} max={info.maxThreshold / scale} />
              <span className="input-group-text">{t(`ruleUnit.${info.unit}`)}</span>
            </div>
          </div>
        )}
        {info.usesWindow && (
          <div className="col-md-4">
            <label className="form-label" htmlFor="r-win">{t('alert.window')}</label>
            <div className="input-group">
              <input id="r-win" type="number" step="any" min={0} max={1440} className="form-control" required value={f.windowMinutes} onChange={set('windowMinutes')} />
              <span className="input-group-text">{t('rules.minutes')}</span>
            </div>
          </div>
        )}
        <div className="col-md-4">
          <label className="form-label" htmlFor="r-cool">{t('rules.cooldown')}</label>
          <div className="input-group">
            <input id="r-cool" type="number" step="any" min={0} max={10080} className="form-control" required value={f.cooldownMinutes} onChange={set('cooldownMinutes')} />
            <span className="input-group-text">{t('rules.minutes')}</span>
          </div>
        </div>
        {info.serverScoped && (
          <div className="col-md-4">
            <label className="form-label" htmlFor="r-srv">{t('rules.scope')}</label>
            <select id="r-srv" className="form-select" value={f.serverId} onChange={set('serverId')}>
              <option value="">{t('rules.allServers')}</option>
              {servers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </div>
        )}
        {info.resourceKind && (
          <div className="col-md-4">
            <label className="form-label" htmlFor="r-res">{t(`rules.resource.${info.resourceKind}`)}</label>
            <input id="r-res" className="form-control nm-ltr" dir="ltr" maxLength={255} value={f.resourceFilter} onChange={set('resourceFilter')}
              placeholder={t('rules.resourceAll')} />
          </div>
        )}
        <div className="col-12">
          <label className="form-label" htmlFor="r-desc">{t('rules.description')}</label>
          <input id="r-desc" className="form-control" maxLength={500} value={f.description} onChange={set('description')} />
        </div>
        <div className="col-12 form-check ms-2">
          <input id="r-en" type="checkbox" className="form-check-input" checked={f.enabled} onChange={set('enabled')} />
          <label className="form-check-label" htmlFor="r-en">{t('rules.enabled')}</label>
        </div>
        <div className="col-12 small text-body-secondary">{t('rules.saveNote')}</div>
        <div className="col-12"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}

export default function AlertRulesPage() {
  const i18n = useI18n();
  const { t, formatNumber } = i18n;
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageAlertRules);
  const rules = useApi(() => alertApi.rules(), []);
  const types = useApi(() => alertApi.ruleTypes(), []);
  const servers = useApi(() => (canManage ? serverApi.search({ page: 0, size: 200, sort: 'name,asc' }) : Promise.resolve({ content: [] })), [canManage]);
  const [editing, setEditing] = useState(null); // rule, 'new' or null
  const [deleting, setDeleting] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);

  const remove = async () => {
    setBusy(true);
    try {
      await alertApi.deleteRule(deleting.id);
      setDeleting(null);
      rules.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  if (!rules.data || !types.data) return rules.error || types.error ? <ErrorAlert error={rules.error || types.error} /> : <LoadingSpinner />;
  return (
    <>
      <PageHeader title={t('rules.title')} subtitle={t('rules.subtitle')}
        breadcrumbs={[{ label: t('alerts.title'), to: '/alerts' }, { label: t('rules.title') }]}
        actions={canManage && <button type="button" className="btn btn-sm btn-primary" onClick={() => setEditing('new')}>
          <i className="bi bi-plus-lg me-1" aria-hidden="true" />{t('rules.new')}</button>} />
      <ErrorAlert error={error} />
      <div className="card">
        <div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr>
              <th>{t('rules.name')}</th><th>{t('alerts.severity')}</th><th>{t('alert.threshold')}</th><th>{t('alert.window')}</th>
              <th>{t('rules.cooldown')}</th><th>{t('rules.scope')}</th><th>{t('alerts.open')}</th><th>{t('rules.state')}</th>{canManage && <th />}
            </tr></thead>
            <tbody>
              {rules.data.map((r) => (
                <tr key={r.id} className={r.enabled ? '' : 'text-body-secondary'}>
                  <td>
                    <div className="fw-semibold">{r.name}</div>
                    <div className="small text-body-secondary">{t(`alertType.${r.ruleType}`)}</div>
                  </td>
                  <td><SeverityBadge severity={r.severity} /></td>
                  <td className="nm-num">{r.threshold == null ? '—' : formatAlertValue(r.unit, r.threshold, i18n)}</td>
                  <td className="small">{windowText(r.windowSeconds, i18n)}</td>
                  <td className="small">{windowText(r.cooldownSeconds, i18n)}</td>
                  <td className="small">
                    {r.serverName || t('rules.allServers')}
                    {r.resourceFilter && <div><code className="nm-ltr" dir="ltr">{r.resourceFilter}</code></div>}
                  </td>
                  <td className="nm-num">{formatNumber(r.activeAlerts)}</td>
                  <td>{r.enabled ? <StatusBadge status="AVAILABLE" label={t('rules.on')} /> : <StatusBadge status="NOT_CONFIGURED" label={t('rules.off')} />}</td>
                  {canManage && (
                    <td className="text-nowrap">
                      <button type="button" className="btn btn-sm btn-outline-primary me-1" onClick={() => setEditing(r)} aria-label={t('common.edit')}>
                        <i className="bi bi-pencil" aria-hidden="true" /></button>
                      <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setDeleting(r)} aria-label={t('common.delete')}>
                        <i className="bi bi-trash" aria-hidden="true" /></button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="card-footer small text-body-secondary">{t('rules.footer')}</div>
      </div>
      {editing && (
        <RuleForm rule={editing === 'new' ? null : editing} types={types.data} servers={servers.data?.content || []}
          onClose={() => setEditing(null)} onSaved={() => { setEditing(null); rules.reload(); }} />
      )}
      <ConfirmDialog show={Boolean(deleting)} title={t('rules.deleteTitle')} confirmLabel={t('common.delete')} busy={busy}
        onConfirm={remove} onCancel={() => setDeleting(null)}>
        {t('rules.deleteText', { name: deleting?.name || '' })}
      </ConfirmDialog>
    </>
  );
}
