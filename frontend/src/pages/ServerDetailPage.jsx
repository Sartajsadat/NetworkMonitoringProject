import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { metricsApi, serverApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { PERMISSIONS } from '../auth/roles.js';
import { useI18n } from '../i18n/I18nContext.jsx';
import ConfirmDialog from '../components/ConfirmDialog.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import AgentTab from './server/AgentTab.jsx';
import MetricsTab from './server/MetricsTab.jsx';
import NetworkTab from './server/NetworkTab.jsx';
import OverviewTab from './server/OverviewTab.jsx';
import ProcessesTab from './server/ProcessesTab.jsx';
import StorageTab from './server/StorageTab.jsx';

const TABS = ['overview', 'metrics', 'network', 'storage', 'processes', 'agent'];
const LIVE_REFRESH_MS = 30000;

export default function ServerDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab = TABS.includes(params.get('tab')) ? params.get('tab') : 'overview';
  const { can } = useAuth();
  const { t } = useI18n();
  const { data: s, error, loading, reload } = useApi(() => serverApi.get(id), [id]);
  const { data: latest, reload: reloadLatest } = useApi(() => metricsApi.latest(id), [id]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState(null);

  useEffect(() => {
    const timer = setInterval(() => { reloadLatest(); }, LIVE_REFRESH_MS);
    return () => clearInterval(timer);
  }, [reloadLatest]);

  const doDelete = async () => {
    setDeleting(true);
    setDeleteError(null);
    try {
      await serverApi.remove(id);
      navigate('/servers', { replace: true });
    } catch (e) {
      setDeleteError(e);
      setConfirmDelete(false);
    } finally {
      setDeleting(false);
    }
  };

  if (loading && !s) return <LoadingSpinner />;
  if (error) return <ErrorAlert error={error} onRetry={reload} />;
  if (!s) return null;
  const canManage = can(PERMISSIONS.manageServers);

  return (
    <>
      <PageHeader
        title={<span className="d-inline-flex align-items-center gap-2 flex-wrap">{s.name} <StatusBadge status={s.connectionStatus} /></span>}
        subtitle={<span className="nm-mono nm-ltr">{s.hostname}</span>}
        breadcrumbs={[{ label: t('servers.title'), to: '/servers' }, { label: s.name }]}
        actions={<>
          {canManage && (
            <Link to={`/servers/${id}/edit`} className="btn btn-sm btn-outline-primary">
              <i className="bi bi-pencil me-1" aria-hidden="true" />{t('common.edit')}
            </Link>
          )}
          {can(PERMISSIONS.deleteServers) && (
            <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setConfirmDelete(true)}>
              <i className="bi bi-trash me-1" aria-hidden="true" />{t('common.delete')}
            </button>
          )}
        </>}
      />
      <ErrorAlert error={deleteError} />

      <ul className="nav nav-tabs nm-tabs mb-3" role="tablist">
        {TABS.map((k) => (
          <li className="nav-item" key={k} role="presentation">
            <button type="button" role="tab" aria-selected={tab === k} className={`nav-link ${tab === k ? 'active' : ''}`}
              onClick={() => setParams({ tab: k }, { replace: true })}>
              {t(`serverTab.${k}`)}
            </button>
          </li>
        ))}
      </ul>

      {tab === 'overview' && <OverviewTab server={s} latest={latest} />}
      {tab === 'metrics' && <MetricsTab serverId={id} latest={latest} key={latest?.collectedAt ? 'with-data' : 'no-data'} />}
      {tab === 'network' && <NetworkTab latest={latest} />}
      {tab === 'storage' && <StorageTab latest={latest} />}
      {tab === 'processes' && <ProcessesTab latest={latest} canManage={canManage} />}
      {tab === 'agent' && <AgentTab serverId={id} latest={latest} canManage={canManage} onChanged={() => { reload(); reloadLatest(); }} />}

      <ConfirmDialog show={confirmDelete} title={t('serverDetail.deleteTitle')} confirmLabel={t('serverDetail.deleteConfirm')} busy={deleting}
        onConfirm={doDelete} onCancel={() => setConfirmDelete(false)}>
        <p>{t('serverDetail.deleteBody', { name: s.name })}</p>
        <p className="mb-0 small text-body-secondary">{t('serverDetail.deleteHint')}</p>
      </ConfirmDialog>
    </>
  );
}
