import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { appApi, firewallApi } from '../../api/endpoints.js';
import { useApi } from '../../api/useApi.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import { AppForm, IdentityText, InternetSwitch, SupportBadge, useRefreshWhilePending } from './AppAccessPage.jsx';
import { RuleStatusBadge, RuleSummary } from './firewallText.jsx';

export default function AppDetailPage() {
  const { id } = useParams();
  const { t } = useI18n();
  const { can } = useAuth();
  const { data: a, error, loading, reload } = useApi(() => appApi.get(id), [id]);
  const policies = useApi(() => firewallApi.rules({ applicationId: id, size: 50 }), [id]);
  const [editing, setEditing] = useState(false);
  const refresh = () => { reload(); policies.reload(); };
  useRefreshWhilePending(a ? [a] : [], refresh);
  if (!a) return <>{loading && <LoadingSpinner />}<ErrorAlert error={error} onRetry={reload} /></>;
  return (
    <>
      <PageHeader title={a.name} subtitle={<IdentityText kind={a.identityKind} value={a.identityValue} />}
        breadcrumbs={[{ label: t('appAccess.title'), to: '/app-access' }, { label: a.name }]}
        actions={can(PERMISSIONS.manageFirewall) && <button type="button" className="btn btn-sm btn-outline-primary" onClick={() => setEditing(true)}>{t('common.edit')}</button>} />
      <div className="row g-3 mb-3">
        <div className="col-lg-6"><div className="card h-100"><div className="card-body">
          <h2 className="h6">{t('appAccess.internet')}</h2>
          <InternetSwitch app={a} onChanged={refresh} />
          <p className="small text-body-secondary mt-3 mb-0">{t('appAccess.modeHelp.BLOCK_INTERNET')}</p>
        </div></div></div>
        <div className="col-lg-6"><div className="card h-100"><div className="card-body">
          <h2 className="h6">{t('appAccess.identity')}</h2>
          <p className="small mb-1"><Link to={`/servers/${a.serverId}`}>{a.serverName}</Link> · <IdentityText kind={a.identityKind} value={a.identityValue} /></p>
          <p className="small mb-2">{a.seenProcesses?.length ? t('appAccess.seenRunning', { list: a.seenProcesses.join(', ') }) : t('appAccess.notSeenLong')}</p>
          <SupportBadge support={a.support} />
          <p className="small mt-2 mb-0">{a.support?.supported ? (a.support.enforcing ? t('appAccess.supportEnforce') : t('appAccess.supportDryRun')) : a.support?.reason}</p>
        </div></div></div>
      </div>
      <div className="card"><div className="card-body">
        <h2 className="h6">{t('appAccess.history')}</h2>
        {policies.data?.content?.length === 0 && <p className="small text-body-secondary mb-0">{t('appAccess.noPolicies')}</p>}
        {policies.data?.content?.length > 0 && (
          <table className="table nm-table mb-0 align-middle"><tbody>
            {policies.data.content.map((r) => (
              <tr key={r.id}><td><Link to={`/ip-policies/${r.id}`}><RuleSummary rule={r} /></Link>
                <div className="small text-body-secondary">{r.requestedBy} · <TimeAgo value={r.requestedAt} /></div></td>
                <td><RuleStatusBadge status={r.status} /></td>
                <td className="small">{r.lastVerifiedAt ? <>{t('appAccess.lastVerified')} <TimeAgo value={r.lastVerifiedAt} /></> : '—'}</td></tr>))}
          </tbody></table>)}
      </div></div>
      {editing && <AppForm app={a} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); reload(); }} />}
    </>
  );
}
