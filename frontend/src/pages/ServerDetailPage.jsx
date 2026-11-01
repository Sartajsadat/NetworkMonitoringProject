import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { serverApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { PERMISSIONS } from '../auth/roles.js';
import { useI18n } from '../i18n/I18nContext.jsx';
import ConfirmDialog from '../components/ConfirmDialog.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import TimeAgo from '../components/TimeAgo.jsx';

function Field({ label, children }) {
  return (
    <div className="col-sm-6 col-lg-4 mb-3">
      <div className="small text-body-secondary">{label}</div>
      <div>{children ?? <span className="text-body-secondary">—</span>}</div>
    </div>
  );
}

export default function ServerDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { can } = useAuth();
  const { t } = useI18n();
  const { data: s, error, loading, reload } = useApi(() => serverApi.get(id), [id]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState(null);

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

  return (
    <>
      <PageHeader
        title={s.name}
        subtitle={<span className="nm-mono nm-ltr">{s.hostname}</span>}
        breadcrumbs={[{ label: t('servers.title'), to: '/servers' }, { label: s.name }]}
        actions={<>
          {can(PERMISSIONS.manageServers) && (
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

      <div className="row g-3">
        <div className="col-lg-8">
          <div className="card mb-3"><div className="card-body">
            <h2 className="h6 mb-3">{t('serverDetail.details')}</h2>
            <div className="row">
              <Field label={t('serverDetail.os')}>{t(`os.${s.osFamily}`)}{s.osVersion && <> · <span className="nm-ltr">{s.osVersion}</span></>}</Field>
              <Field label={t('serverDetail.environment')}>{t(`env.${s.environment}`)}</Field>
              <Field label={t('serverDetail.organization')}>{s.organization}</Field>
              <Field label={t('serverDetail.purpose')}>{s.purpose}</Field>
              <Field label={t('serverDetail.location')}>{s.location}</Field>
              <Field label={t('serverDetail.monitoringStatus')}><StatusBadge status={s.monitoringStatus} /></Field>
            </div>
            {s.notes && <><div className="small text-body-secondary">{t('serverDetail.notes')}</div><div style={{ whiteSpace: 'pre-wrap' }}>{s.notes}</div></>}
          </div></div>

          <div className="card mb-3">
            <div className="card-header"><h2 className="h6 mb-0">{t('serverDetail.ips')}</h2></div>
            {s.ipAddresses.length === 0 ? (
              <div className="card-body small text-body-secondary">{t('serverDetail.noIps')}</div>
            ) : (
              <table className="table table-sm nm-table">
                <thead><tr><th>{t('serverDetail.address')}</th><th>{t('serverDetail.version')}</th><th>{t('serverDetail.label')}</th></tr></thead>
                <tbody>{s.ipAddresses.map((ip) => (
                  <tr key={ip.address}><td className="nm-mono"><span className="nm-ltr">{ip.address}</span></td><td className="nm-ltr">IPv{ip.version}</td><td>{ip.label || '—'}</td></tr>
                ))}</tbody>
              </table>
            )}
          </div>

          <div className="card">
            <div className="card-header"><h2 className="h6 mb-0">{t('serverDetail.metrics')}</h2></div>
            <div className="card-body">
              <StatusBadge status="NOT_COLLECTED" />
              <p className="small text-body-secondary mb-0 mt-2">{t('serverDetail.metricsText')}</p>
            </div>
          </div>
        </div>

        <div className="col-lg-4">
          <div className="card mb-3"><div className="card-body">
            <h2 className="h6 mb-3">{t('serverDetail.agent')}</h2>
            <div className="mb-2"><span className="small text-body-secondary d-block">{t('serverDetail.connection')}</span><StatusBadge status={s.connectionStatus} /></div>
            <div className="mb-2"><span className="small text-body-secondary d-block">{t('serverDetail.agentHealth')}</span><StatusBadge status={s.agentHealth} /></div>
            <div className="mb-2"><span className="small text-body-secondary d-block">{t('serverDetail.agentVersion')}</span>{s.agentVersion ? <span className="nm-ltr">{s.agentVersion}</span> : <span className="text-body-secondary">{t('serverDetail.notInstalled')}</span>}</div>
            <div><span className="small text-body-secondary d-block">{t('serverDetail.lastComm')}</span><TimeAgo value={s.lastSeenAt} /></div>
          </div></div>

          <div className="card mb-3"><div className="card-body">
            <h2 className="h6 mb-2">{t('serverDetail.groups')}</h2>
            {s.groups.length === 0 ? <div className="small text-body-secondary">{t('serverDetail.noGroups')}</div> : (
              <ul className="list-unstyled mb-0">
                {s.groups.map((g) => (
                  <li key={g.id}><Link to={`/servers?groupId=${g.id}`}>{g.name}</Link> <small className="text-body-secondary">({t(`groupType.${g.groupType}`)})</small></li>
                ))}
              </ul>
            )}
          </div></div>

          <div className="card"><div className="card-body small">
            <h2 className="h6 mb-2">{t('serverDetail.record')}</h2>
            <div>{t('serverDetail.registered')}: <TimeAgo value={s.createdAt} />{s.createdBy && <> {t('serverDetail.by')} <strong className="nm-ltr">{s.createdBy}</strong></>}</div>
            <div>{t('serverDetail.modified')}: <TimeAgo value={s.updatedAt} />{s.updatedBy && <> {t('serverDetail.by')} <strong className="nm-ltr">{s.updatedBy}</strong></>}</div>
          </div></div>
        </div>
      </div>

      <ConfirmDialog show={confirmDelete} title={t('serverDetail.deleteTitle')} confirmLabel={t('serverDetail.deleteConfirm')} busy={deleting}
        onConfirm={doDelete} onCancel={() => setConfirmDelete(false)}>
        <p>{t('serverDetail.deleteBody', { name: s.name })}</p>
        <p className="mb-0 small text-body-secondary">{t('serverDetail.deleteHint')}</p>
      </ConfirmDialog>
    </>
  );
}
