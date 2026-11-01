import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { captureApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import { CAPTURE_FILTER_PRESETS, DURATION_OPTIONS, MB, SNAPLEN_OPTIONS, durationText } from './captureHelpers.js';

export default function NewCapturePage() {
  const i18n = useI18n();
  const { t, formatNumber, units } = i18n;
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const caps = useApi(() => captureApi.capabilities(), []);
  const limits = useApi(() => captureApi.limits(), []);
  const [form, setForm] = useState({
    serverId: params.get('serverId') || '', interfaceName: '', captureFilter: '', durationSeconds: 300, maxMegabytes: 100,
    snaplen: 256, retentionDays: '', name: '', reference: '', reason: '', description: '',
  });
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setForm((f) => ({ ...f, [k]: e.target.value }));

  const servers = useMemo(() => [...(caps.data || [])].sort((a, b) => a.serverName.localeCompare(b.serverName)), [caps.data]);
  const cap = servers.find((s) => s.serverId === form.serverId);
  const l = limits.data;
  const maxDuration = Math.min(l?.maxDurationSeconds ?? 3600, cap?.maxDurationSeconds ?? Infinity);
  const maxMb = Math.floor(Math.min(l?.maxBytesPerCapture ?? 1024 * MB, cap?.maxBytes ?? Infinity) / MB);
  const usable = cap?.state === 'AVAILABLE' && !cap.busy;

  // Pick the first interface when the server changes; keep values inside the server's limits.
  useEffect(() => {
    if (!cap) return;
    setForm((f) => ({
      ...f,
      interfaceName: cap.interfaces?.some((i) => i.name === f.interfaceName) ? f.interfaceName : (cap.interfaces?.[0]?.name || ''),
      durationSeconds: Math.min(f.durationSeconds, maxDuration),
      maxMegabytes: Math.min(f.maxMegabytes, maxMb),
    }));
  }, [cap, maxDuration, maxMb]);
  useEffect(() => {
    if (l && form.retentionDays === '') setForm((f) => ({ ...f, retentionDays: l.defaultRetentionDays, snaplen: l.defaultSnaplen }));
  }, [l, form.retentionDays]);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const created = await captureApi.create({
        ...form,
        captureFilter: form.captureFilter.trim() || null,
        reference: form.reference.trim() || null,
        description: form.description.trim() || null,
        durationSeconds: Number(form.durationSeconds),
        maxMegabytes: Number(form.maxMegabytes),
        snaplen: Number(form.snaplen),
        retentionDays: Number(form.retentionDays),
      });
      navigate(`/captures/${created.id}`);
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };

  if (!caps.data && caps.loading) return <LoadingSpinner />;
  return (
    <>
      <PageHeader title={t('captureNew.title')} subtitle={t('captureNew.subtitle')}
        breadcrumbs={[{ label: t('captures.title'), to: '/captures' }, { label: t('captureNew.title') }]} />
      <ErrorAlert error={caps.error || limits.error} onRetry={() => { caps.reload(); limits.reload(); }} />

      <form onSubmit={submit} className="row g-3">
        <div className="col-lg-8">
          <div className="card mb-3"><div className="card-body">
            <h2 className="h6">{t('captureNew.where')}</h2>
            <div className="row g-3">
              <div className="col-md-6">
                <label className="form-label" htmlFor="cap-server">{t('captureNew.server')}</label>
                <select id="cap-server" className="form-select" required value={form.serverId} onChange={set('serverId')}>
                  <option value="">{t('captureNew.chooseServer')}</option>
                  {servers.map((s) => (
                    <option key={s.serverId} value={s.serverId}>
                      {s.serverName} — {t(`capture.state.${s.state}`)}{s.busy ? ` (${t('captureNew.busy')})` : ''}
                    </option>
                  ))}
                </select>
              </div>
              <div className="col-md-6">
                <label className="form-label" htmlFor="cap-iface">{t('captureNew.interface')}</label>
                <select id="cap-iface" className="form-select" required disabled={!usable} value={form.interfaceName} onChange={set('interfaceName')}>
                  {(cap?.interfaces || []).map((i) => (
                    <option key={i.name} value={i.name}>{i.description ? `${i.description} — ${i.name}` : i.name}</option>
                  ))}
                </select>
              </div>
            </div>
            {cap && !usable && (
              <div className="alert alert-warning mt-3 mb-0 d-flex gap-2" role="status">
                <StatusBadge status={cap.busy ? 'RUNNING' : cap.state} label={cap.busy ? t('captureNew.busy') : t(`capture.state.${cap.state}`)} />
                <div className="small">
                  {cap.busy ? t('captureNew.busyHint') : t(`capture.stateHint.${cap.state}`)}
                  {cap.message && <div className="text-body-secondary nm-ltr mt-1">{cap.message}</div>}
                </div>
              </div>
            )}
          </div></div>

          <div className="card mb-3"><div className="card-body">
            <h2 className="h6">{t('captureNew.what')}</h2>
            <label className="form-label" htmlFor="cap-filter">{t('captureNew.captureFilter')}</label>
            <input id="cap-filter" className="form-control nm-mono nm-ltr" dir="ltr" maxLength={512} value={form.captureFilter}
              onChange={set('captureFilter')} placeholder="tcp port 443 and host 10.0.0.10" aria-describedby="cap-filter-help" />
            <div className="d-flex flex-wrap gap-1 mt-2" aria-label={t('captureNew.presets')}>
              {CAPTURE_FILTER_PRESETS.map((p) => (
                <button key={p.key} type="button" className={`btn btn-sm ${form.captureFilter === p.filter ? 'btn-primary' : 'btn-outline-secondary'}`}
                  onClick={() => setForm((f) => ({ ...f, captureFilter: p.filter }))} title={p.filter || undefined}>
                  {t(`captureNew.preset.${p.key}`)}
                </button>
              ))}
            </div>
            <div id="cap-filter-help" className="form-text">{t('captureNew.captureFilterHelp')}</div>

            <div className="row g-3 mt-1">
              <div className="col-sm-6 col-md-4">
                <label className="form-label" htmlFor="cap-duration">{t('captureNew.duration')}</label>
                <select id="cap-duration" className="form-select" value={form.durationSeconds} onChange={set('durationSeconds')}>
                  {DURATION_OPTIONS.filter((d) => d <= maxDuration).map((d) => (
                    <option key={d} value={d}>{durationText(d, i18n)}</option>
                  ))}
                </select>
              </div>
              <div className="col-sm-6 col-md-4">
                <label className="form-label" htmlFor="cap-size">{t('captureNew.maxSize')}</label>
                <div className="input-group">
                  <input id="cap-size" type="number" className="form-control" min={1} max={maxMb} required value={form.maxMegabytes} onChange={set('maxMegabytes')} />
                  <span className="input-group-text">{t('unit.MB')}</span>
                </div>
                <div className="form-text">{t('captureNew.maxSizeHelp', { n: formatNumber(maxMb) })}</div>
              </div>
              <div className="col-md-8 order-md-last">
                <label className="form-label" htmlFor="cap-snaplen">{t('captureNew.snaplen')}</label>
                <select id="cap-snaplen" className="form-select" value={form.snaplen} onChange={set('snaplen')}>
                  {SNAPLEN_OPTIONS.map((o) => <option key={o.value} value={o.value}>{t('captureNew.snaplenOption', { label: t(`captureNew.snaplen.${o.key}`), n: formatNumber(o.value) })}</option>)}
                </select>
              </div>
              <div className="col-sm-6 col-md-4">
                <label className="form-label" htmlFor="cap-retention">{t('captureNew.retention')}</label>
                <div className="input-group">
                  <input id="cap-retention" type="number" className="form-control" min={1} max={l?.maxRetentionDays ?? 90} required
                    value={form.retentionDays} onChange={set('retentionDays')} />
                  <span className="input-group-text">{t('captureNew.days')}</span>
                </div>
              </div>
            </div>
            <div className="form-text">{t('captureNew.snaplenHelp')}</div>
          </div></div>

          <div className="card mb-3"><div className="card-body">
            <h2 className="h6">{t('captureNew.why')}</h2>
            <div className="row g-3">
              <div className="col-md-7">
                <label className="form-label" htmlFor="cap-name">{t('captureNew.name')}</label>
                <input id="cap-name" className="form-control" required maxLength={120} value={form.name} onChange={set('name')} />
              </div>
              <div className="col-md-5">
                <label className="form-label" htmlFor="cap-ref">{t('captureNew.reference')}</label>
                <input id="cap-ref" className="form-control" maxLength={120} value={form.reference} onChange={set('reference')} placeholder="INC-2026-0001" />
              </div>
              <div className="col-12">
                <label className="form-label" htmlFor="cap-reason">{t('captureNew.reason')}</label>
                <textarea id="cap-reason" className="form-control" rows={2} required minLength={10} maxLength={500} value={form.reason} onChange={set('reason')} />
                <div className="form-text">{t('captureNew.reasonHelp')}</div>
              </div>
            </div>
          </div></div>
        </div>

        <div className="col-lg-4">
          <div className="card mb-3 border-warning-subtle"><div className="card-body small">
            <h2 className="h6"><i className="bi bi-shield-exclamation me-1 text-warning" aria-hidden="true" />{t('captureNew.rulesTitle')}</h2>
            <ul className="ps-3 mb-2">
              <li>{t('captureNew.rule1')}</li>
              <li>{t('captureNew.rule2')}</li>
              <li>{t('captureNew.rule3')}</li>
              <li>{t('captureNew.rule4')}</li>
            </ul>
            <div className="form-check">
              <input id="cap-confirm" type="checkbox" className="form-check-input" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
              <label className="form-check-label fw-semibold" htmlFor="cap-confirm">{t('captureNew.confirm')}</label>
            </div>
          </div></div>
          <div className="card mb-3"><div className="card-body small">
            <h2 className="h6">{t('captureNew.filtersTitle')}</h2>
            <p className="mb-2"><strong>{t('captureNew.captureFilter')}:</strong> {t('captureNew.filtersCapture')}</p>
            <p className="mb-0"><strong>{t('analyzer.displayFilter')}:</strong> {t('captureNew.filtersDisplay')}</p>
          </div></div>
          {l && (
            <div className="small text-body-secondary mb-3">
              {t('captures.storageOf', { used: units.bytes(l.usedBytes), total: units.bytes(l.quotaBytes) })}
            </div>
          )}
          <ErrorAlert error={error} />
          <div className="d-flex gap-2">
            <button type="submit" className="btn btn-primary" disabled={busy || !usable || !confirmed}>
              {busy && <span className="spinner-border spinner-border-sm me-1" aria-hidden="true" />}
              <i className="bi bi-record-circle me-1" aria-hidden="true" />{t('captureNew.start')}
            </button>
            <Link to="/captures" className="btn btn-outline-secondary">{t('common.cancel')}</Link>
          </div>
        </div>
      </form>
    </>
  );
}
