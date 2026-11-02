import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { firewallApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Pagination from '../../components/Pagination.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import { RULE_FILTERS, RuleStatusBadge, RuleSummary } from './firewallText.jsx';

export default function FirewallRulesPage() {
  const { t, formatDateTime } = useI18n();
  const { can } = useAuth();
  const [params, setParams] = useSearchParams();
  const filter = params.get('status') || 'pending';
  const integrationId = params.get('integrationId') || '';
  const [address, setAddress] = useState(params.get('address') || '');
  const [page, setPage] = useState(0);
  const targets = useApi(() => firewallApi.integrations(), []);
  const { data, error, loading, reload } = useApi(() => firewallApi.rules({
    status: RULE_FILTERS[filter] || [], integrationId: integrationId || undefined, address: params.get('address') || undefined, page, size: 50,
  }), [filter, integrationId, params.get('address'), page]);
  const update = (patch) => {
    const next = new URLSearchParams(params);
    Object.entries(patch).forEach(([k, v]) => (v ? next.set(k, v) : next.delete(k)));
    setParams(next);
    setPage(0);
  };
  return (
    <>
      <PageHeader title={t('fwRules.title')} subtitle={t('fwRules.subtitle')} breadcrumbs={[{ label: t('fwRules.title') }]}
        actions={can(PERMISSIONS.requestFirewallRules) && (
          <Link to="/ip-policies/new" className="btn btn-sm btn-primary"><i className="bi bi-plus-lg me-1" aria-hidden="true" />{t('fwRules.new')}</Link>)} />
      <ul className="nav nav-tabs mb-3" role="tablist">
        {Object.keys(RULE_FILTERS).map((k) => (
          <li className="nav-item" key={k}>
            <button type="button" role="tab" aria-selected={filter === k} className={`nav-link ${filter === k ? 'active' : ''}`}
              onClick={() => update({ status: k === 'pending' ? '' : k })}>{t(`fwRules.filter.${k}`)}</button>
          </li>
        ))}
      </ul>
      <form className="row g-2 mb-3" onSubmit={(e) => { e.preventDefault(); update({ address }); }}>
        <div className="col-sm-5 col-lg-4">
          <label className="visually-hidden" htmlFor="r-target">{t('fw.target')}</label>
          <select id="r-target" className="form-select form-select-sm" value={integrationId} onChange={(e) => update({ integrationId: e.target.value })}>
            <option value="">{t('fwRules.allTargets')}</option>
            {(targets.data || []).map((i) => <option key={i.id} value={i.id}>{i.kind === 'SERVER_AGENT' ? i.serverName : i.name}</option>)}
          </select>
        </div>
        <div className="col-sm-5 col-lg-4">
          <label className="visually-hidden" htmlFor="r-addr">{t('fwRules.address')}</label>
          <input id="r-addr" className="form-control form-control-sm nm-ltr" dir="ltr" placeholder={t('fwRules.address')} value={address}
            onChange={(e) => setAddress(e.target.value)} />
        </div>
        <div className="col-auto"><button type="submit" className="btn btn-sm btn-outline-primary">{t('common.search')}</button></div>
      </form>
      <ErrorAlert error={error} onRetry={reload} />
      {!data && loading && <LoadingSpinner />}
      {data && data.content.length === 0 && (
        <div className="card"><EmptyState icon="shield-lock" title={t('fwRules.none')}>{t('fwRules.noneHint')}</EmptyState></div>)}
      {data && data.content.length > 0 && (
        <div className="card"><div className="table-responsive">
          <table className="table nm-table mb-0 align-middle">
            <thead><tr>
              <th>{t('fwRules.rule')}</th><th>{t('fw.target')}</th><th>{t('fwRules.status')}</th><th>{t('fwRules.requested')}</th><th>{t('fwRules.expires')}</th>
            </tr></thead>
            <tbody>
              {data.content.map((r) => (
                <tr key={r.id}>
                  <td><Link to={`/ip-policies/${r.id}`} className="text-decoration-none"><RuleSummary rule={r} /></Link>
                    <div className="small text-body-secondary text-truncate" style={{ maxWidth: '28rem' }}>{r.reason}</div></td>
                  <td className="small">{r.integrationKind === 'SERVER_AGENT' ? r.serverName : r.integrationName}
                    <div className="text-body-secondary">{r.integrationKind === 'SERVER_AGENT' ? t('fw.scopeShort.server') : t('fw.scopeShort.central')}</div></td>
                  <td><RuleStatusBadge status={r.status} /></td>
                  <td className="small">{r.requestedBy}<div className="text-body-secondary"><TimeAgo value={r.requestedAt} /></div></td>
                  <td className="small">{r.expiresAt ? formatDateTime(r.expiresAt) : <span className="text-body-secondary">{t('fwRules.noExpiry')}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div></div>
      )}
      {data && <Pagination data={data} onPage={setPage} />}
    </>
  );
}
