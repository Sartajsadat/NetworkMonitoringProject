import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { eventApi, serverApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Pagination from '../../components/Pagination.jsx';
import SeverityBadge from '../../components/SeverityBadge.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeRangePicker, { resolveRange } from '../../components/TimeRangePicker.jsx';
import { CATEGORIES, CATEGORY_ICON, SEVERITIES, eventSummary, eventTitle } from './eventText.js';

const SEV_COLOR = { INFO: 'var(--nm-chart-1)', WARNING: 'var(--nm-chart-4)', ERROR: 'var(--nm-chart-2)', CRITICAL: '#6f1d1b' };

/** Filters live in the URL so a link (from a server, an alert, the dashboard) opens a prepared view. */
function useFilters() {
  const [params, setParams] = useSearchParams();
  const range = params.get('from') && params.get('to')
    ? { from: params.get('from'), to: params.get('to') }
    : { preset: params.get('range') || '24h' };
  const f = {
    range,
    serverId: params.get('serverId') || '',
    ip: params.get('ip') || '',
    category: params.getAll('category'),
    minSeverity: params.get('minSeverity') || '',
    service: params.get('service') || '',
    alertStatus: params.get('alertStatus') || '',
    text: params.get('text') || '',
    correlationId: params.get('correlationId') || '',
    order: params.get('order') || 'desc',
    page: Number(params.get('page') || 0),
  };
  const update = (changes) => {
    const next = { ...f, page: 0, ...changes };
    const out = new URLSearchParams();
    if (next.range.preset) out.set('range', next.range.preset);
    else { out.set('from', next.range.from); out.set('to', next.range.to); }
    ['serverId', 'ip', 'minSeverity', 'service', 'alertStatus', 'text', 'correlationId'].forEach((k) => next[k] && out.set(k, next[k]));
    next.category.forEach((c) => out.append('category', c));
    if (next.order !== 'desc') out.set('order', next.order);
    if (next.page) out.set('page', String(next.page));
    setParams(out, { replace: true });
  };
  return [f, update];
}

export default function TimelinePage() {
  const i18n = useI18n();
  const { t, formatNumber, formatDateTime, formatAxisTime, formatAxisTimeSeconds } = i18n;
  const { can } = useAuth();
  const [f, update] = useFilters();
  const [draft, setDraft] = useState({ ip: f.ip, service: f.service, text: f.text });
  useEffect(() => setDraft({ ip: f.ip, service: f.service, text: f.text }), [f.ip, f.service, f.text]);
  const servers = useApi(() => serverApi.search({ page: 0, size: 200, sort: 'name,asc' }), []);
  const resolved = resolveRange(f.range);
  const query = {
    from: resolved.from, to: resolved.to, serverId: f.serverId || undefined, ip: f.ip || undefined,
    category: f.category.length ? f.category : undefined, minSeverity: f.minSeverity || undefined,
    service: f.service || undefined, alertStatus: f.alertStatus || undefined, text: f.text || undefined,
    correlationId: f.correlationId || undefined,
  };
  const key = JSON.stringify({ ...query, from: f.range.preset || query.from, to: f.range.preset ? '' : query.to });
  const list = useApi(() => eventApi.search({ ...query, page: f.page, size: 50, order: f.order }), [key, f.page, f.order]);
  const hist = useApi(() => eventApi.histogram(query), [key]);

  useEffect(() => {
    if (!resolved.live) return undefined;
    const timer = setInterval(() => { list.reload(); hist.reload(); }, 60000);
    return () => clearInterval(timer);
  }, [resolved.live, list.reload, hist.reload]);

  const visibleCategories = CATEGORIES.filter((c) => (c !== 'SECURITY' || can(PERMISSIONS.viewSecurityEvents))
    && (c !== 'CAPTURE' || can(PERMISSIONS.viewCaptures)));
  const toggleCategory = (c) => update({ category: f.category.includes(c) ? f.category.filter((x) => x !== c) : [...f.category, c] });

  const chart = useMemo(() => (hist.data?.buckets || []).map((b) => ({
    t: new Date(b.t).getTime(), INFO: b.info, WARNING: b.warning, ERROR: b.error, CRITICAL: b.critical,
  })), [hist.data]);
  const bucket = hist.data?.bucketSeconds || 60;
  const axis = (v) => (bucket < 3600 ? formatAxisTimeSeconds(v) : formatAxisTime(v, bucket >= 86400 || chart.length > 30));

  // group the page by calendar day for reading
  const groups = [];
  (list.data?.content || []).forEach((e) => {
    const day = new Date(e.occurredAt).toDateString();
    if (!groups.length || groups[groups.length - 1].day !== day) groups.push({ day, at: e.occurredAt, items: [] });
    groups[groups.length - 1].items.push(e);
  });

  return (
    <>
      <PageHeader title={t('timeline.title')} subtitle={t('timeline.subtitle')} breadcrumbs={[{ label: t('timeline.title') }]}
        actions={<TimeRangePicker value={f.range} onChange={(range) => update({ range })} />} />

      {f.correlationId && (
        <div className="alert alert-info small d-flex justify-content-between align-items-center">
          <span><i className="bi bi-link-45deg me-1" aria-hidden="true" />{t('timeline.correlated')}</span>
          <button type="button" className="btn btn-sm btn-outline-secondary" onClick={() => update({ correlationId: '' })}>{t('common.clear')}</button>
        </div>
      )}
      <div className="card mb-3"><div className="card-body">
        <div className="row g-2 align-items-end">
          <div className="col-md-4">
            <label className="form-label small" htmlFor="tl-server">{t('timeline.server')}</label>
            <select id="tl-server" className="form-select form-select-sm" value={f.serverId} onChange={(e) => update({ serverId: e.target.value })}>
              <option value="">{t('timeline.allServers')}</option>
              {(servers.data?.content || []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </div>
          <div className="col-md-4">
            <label className="form-label small" htmlFor="tl-sev">{t('timeline.minSeverity')}</label>
            <select id="tl-sev" className="form-select form-select-sm" value={f.minSeverity} onChange={(e) => update({ minSeverity: e.target.value })}>
              <option value="">{t('common.all')}</option>
              {SEVERITIES.map((s) => <option key={s} value={s}>{t('timeline.atLeast', { s: t(`severity.${s}`) })}</option>)}
            </select>
          </div>
          <div className="col-md-4">
            <label className="form-label small" htmlFor="tl-alert">{t('timeline.alertStatus')}</label>
            <select id="tl-alert" className="form-select form-select-sm" value={f.alertStatus} onChange={(e) => update({ alertStatus: e.target.value })}>
              <option value="">{t('common.all')}</option>
              {['ANY', 'ACTIVE', 'OPEN', 'ACKNOWLEDGED', 'RESOLVED'].map((s) => <option key={s} value={s}>{t(`timeline.alert.${s}`)}</option>)}
            </select>
          </div>
        </div>
        <form className="row g-2 align-items-end mt-0" onSubmit={(e) => { e.preventDefault(); update(draft); }}>
            <div className="col-md-5">
              <label className="form-label small" htmlFor="tl-text">{t('common.search')}</label>
              <input id="tl-text" className="form-control form-control-sm" maxLength={200} value={draft.text}
                onChange={(e) => setDraft({ ...draft, text: e.target.value })} />
            </div>
            <div className="col-6 col-md-3">
              <label className="form-label small" htmlFor="tl-ip">{t('timeline.ip')}</label>
              <input id="tl-ip" className="form-control form-control-sm nm-ltr" dir="ltr" maxLength={45} value={draft.ip}
                onChange={(e) => setDraft({ ...draft, ip: e.target.value })} />
            </div>
            <div className="col-6 col-md-3">
              <label className="form-label small" htmlFor="tl-svc">{t('timeline.service')}</label>
              <input id="tl-svc" className="form-control form-control-sm nm-ltr" dir="ltr" maxLength={255} value={draft.service}
                onChange={(e) => setDraft({ ...draft, service: e.target.value })} />
            </div>
            <div className="col-md-1"><button type="submit" className="btn btn-sm btn-outline-primary w-100">{t('common.apply')}</button></div>
        </form>
        <div className="d-flex flex-wrap gap-1 mt-2" role="group" aria-label={t('timeline.categories')}>
          {visibleCategories.map((c) => (
            <button key={c} type="button" aria-pressed={f.category.includes(c)} onClick={() => toggleCategory(c)}
              className={`btn btn-sm ${f.category.includes(c) ? 'btn-primary' : 'btn-outline-secondary'}`}>
              <i className={`bi bi-${CATEGORY_ICON[c]} me-1`} aria-hidden="true" />{t(`category.${c}`)}
              {hist.data?.byCategory?.[c] != null && <span className="ms-1 nm-num">({formatNumber(hist.data.byCategory[c])})</span>}
            </button>
          ))}
          {f.category.length > 0 && <button type="button" className="btn btn-sm btn-link" onClick={() => update({ category: [] })}>{t('common.clear')}</button>}
        </div>
      </div></div>

      {hist.data && hist.data.total > 0 && (
        <div className="card mb-3"><div className="card-body">
          <div className="d-flex justify-content-between flex-wrap gap-2 small mb-1">
            <h2 className="h6 mb-0">{t('timeline.overTime')}</h2>
            <span className="d-flex gap-2 flex-wrap">
              {SEVERITIES.filter((s) => hist.data.bySeverity[s]).map((s) => (
                <span key={s}><SeverityBadge severity={s} /> <span className="nm-num">{formatNumber(hist.data.bySeverity[s])}</span></span>
              ))}
            </span>
          </div>
          <div style={{ width: '100%', height: 160 }} dir="ltr" role="img" aria-label={t('timeline.overTime')}>
            <ResponsiveContainer>
              <BarChart data={chart} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                <CartesianGrid stroke="var(--nm-grid)" vertical={false} />
                <XAxis dataKey="t" tickFormatter={axis} tick={{ fill: 'var(--nm-axis-text)', fontSize: 11 }} minTickGap={24} />
                <YAxis allowDecimals={false} width={36} tickFormatter={(v) => formatNumber(v)} tick={{ fill: 'var(--nm-axis-text)', fontSize: 11 }}
                  axisLine={false} tickLine={false} />
                <Tooltip labelFormatter={(v) => formatDateTime(new Date(v).toISOString())} formatter={(v, n) => [formatNumber(v), t(`severity.${n}`)]} />
                <Legend formatter={(n) => t(`severity.${n}`)} wrapperStyle={{ fontSize: 12 }} />
                {SEVERITIES.map((s) => <Bar key={s} dataKey={s} stackId="s" fill={SEV_COLOR[s]} isAnimationActive={false} />)}
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div></div>
      )}

      <ErrorAlert error={list.error || hist.error} onRetry={() => { list.reload(); hist.reload(); }} />
      {!list.data && list.loading && <LoadingSpinner />}
      {list.data && list.data.totalElements === 0 && (
        <div className="card"><EmptyState icon="clock-history" title={t('timeline.empty')}>{t('timeline.emptyHint')}</EmptyState></div>
      )}
      {list.data && list.data.totalElements > 0 && (
        <div className="card">
          <div className="card-header d-flex justify-content-between align-items-center small">
            <span>{t('timeline.count', { n: formatNumber(list.data.totalElements) })}</span>
            <div className="btn-group btn-group-sm" role="group" aria-label={t('timeline.order')}>
              <button type="button" className={`btn ${f.order === 'desc' ? 'btn-primary' : 'btn-outline-secondary'}`} onClick={() => update({ order: 'desc' })}>{t('timeline.newest')}</button>
              <button type="button" className={`btn ${f.order === 'asc' ? 'btn-primary' : 'btn-outline-secondary'}`} onClick={() => update({ order: 'asc' })}>{t('timeline.oldest')}</button>
            </div>
          </div>
          <ol className="list-unstyled mb-0 nm-timeline">
            {groups.map((g) => (
              <li key={g.day}>
                <div className="nm-timeline-day">{formatDateTime(g.at).split(/[,،]/)[0]}</div>
                <ol className="list-unstyled mb-0">
                  {g.items.map((e) => (
                    <li key={e.id} className={`nm-timeline-item nm-sev-${e.severity.toLowerCase()}`}>
                      <div className="nm-timeline-time nm-num" title={new Date(e.occurredAt).toISOString()}>
                        {formatAxisTimeSeconds(new Date(e.occurredAt).getTime())}
                      </div>
                      <div className="flex-grow-1 min-w-0">
                        <div className="d-flex flex-wrap align-items-center gap-2">
                          <SeverityBadge severity={e.severity} />
                          <span className="badge text-bg-light border"><i className={`bi bi-${CATEGORY_ICON[e.category]} me-1`} aria-hidden="true" />{t(`category.${e.category}`)}</span>
                          <Link to={`/events/${e.id}`} className="fw-semibold">{eventTitle(e, t)}</Link>
                          {e.serverName && (e.serverId
                            ? <Link to={`/timeline?serverId=${e.serverId}`} className="small text-body-secondary">{e.serverName}</Link>
                            : <span className="small text-body-secondary">{e.serverName}</span>)}
                          {e.resource && <code className="small nm-ltr" dir="ltr">{e.resource}</code>}
                          {e.alertStatus && <StatusBadge status={e.alertStatus === 'RESOLVED' ? 'SUCCESS' : 'DEGRADED'} label={t(`alertStatus.${e.alertStatus}`)} />}
                        </div>
                        <div className="small mt-1">{eventSummary(e, i18n)}</div>
                      </div>
                    </li>
                  ))}
                </ol>
              </li>
            ))}
          </ol>
          <div className="card-footer"><Pagination data={list.data} onPage={(page) => update({ page })} /></div>
        </div>
      )}
    </>
  );
}
