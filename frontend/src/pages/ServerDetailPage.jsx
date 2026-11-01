import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { serverApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { PERMISSIONS } from '../auth/roles.js';
import ConfirmDialog from '../components/ConfirmDialog.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import PageHeader from '../components/PageHeader.jsx';
import StatusBadge from '../components/StatusBadge.jsx';
import TimeAgo from '../components/TimeAgo.jsx';
import { ENV_LABELS, GROUP_TYPE_LABELS, OS_LABELS } from './labels.js';

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
        subtitle={<span className="font-monospace">{s.hostname}</span>}
        breadcrumbs={[{ label: 'Server inventory', to: '/servers' }, { label: s.name }]}
        actions={<>
          {can(PERMISSIONS.manageServers) && (
            <Link to={`/servers/${id}/edit`} className="btn btn-sm btn-outline-primary">
              <i className="bi bi-pencil me-1" aria-hidden="true" />Edit
            </Link>
          )}
          {can(PERMISSIONS.deleteServers) && (
            <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setConfirmDelete(true)}>
              <i className="bi bi-trash me-1" aria-hidden="true" />Delete
            </button>
          )}
        </>}
      />
      <ErrorAlert error={deleteError} />

      <div className="row g-3">
        <div className="col-lg-8">
          <div className="card mb-3"><div className="card-body">
            <h2 className="h6 mb-3">Details</h2>
            <div className="row">
              <Field label="Operating system">{OS_LABELS[s.osFamily]}{s.osVersion && ` · ${s.osVersion}`}</Field>
              <Field label="Environment">{ENV_LABELS[s.environment]}</Field>
              <Field label="Organization / department">{s.organization}</Field>
              <Field label="Purpose">{s.purpose}</Field>
              <Field label="Location / data centre">{s.location}</Field>
              <Field label="Monitoring status"><StatusBadge status={s.monitoringStatus} /></Field>
            </div>
            {s.notes && <><div className="small text-body-secondary">Notes</div><div style={{ whiteSpace: 'pre-wrap' }}>{s.notes}</div></>}
          </div></div>

          <div className="card mb-3">
            <div className="card-header bg-body"><h2 className="h6 mb-0">IP addresses</h2></div>
            {s.ipAddresses.length === 0 ? (
              <div className="card-body small text-body-secondary">No IP addresses recorded.</div>
            ) : (
              <table className="table table-sm mb-0">
                <thead><tr><th>Address</th><th>Version</th><th>Label</th></tr></thead>
                <tbody>{s.ipAddresses.map((ip) => (
                  <tr key={ip.address}><td className="font-monospace">{ip.address}</td><td>IPv{ip.version}</td><td>{ip.label || '—'}</td></tr>
                ))}</tbody>
              </table>
            )}
          </div>

          <div className="card">
            <div className="card-header bg-body"><h2 className="h6 mb-0">Metrics</h2></div>
            <div className="card-body">
              <StatusBadge status="NOT_COLLECTED" />
              <p className="small text-body-secondary mb-0 mt-2">
                CPU, memory, disk, network interface, process and service data will appear here after a monitoring agent
                is installed on this server (Phase 2 for Linux, Phase 3 for Windows). Nothing is estimated or simulated.
              </p>
            </div>
          </div>
        </div>

        <div className="col-lg-4">
          <div className="card mb-3"><div className="card-body">
            <h2 className="h6 mb-3">Agent</h2>
            <div className="mb-2"><span className="small text-body-secondary d-block">Connection</span><StatusBadge status={s.connectionStatus} /></div>
            <div className="mb-2"><span className="small text-body-secondary d-block">Agent health</span><StatusBadge status={s.agentHealth} /></div>
            <div className="mb-2"><span className="small text-body-secondary d-block">Agent version</span>{s.agentVersion || <span className="text-body-secondary">Not installed</span>}</div>
            <div><span className="small text-body-secondary d-block">Last successful communication</span><TimeAgo value={s.lastSeenAt} /></div>
          </div></div>

          <div className="card mb-3"><div className="card-body">
            <h2 className="h6 mb-2">Groups</h2>
            {s.groups.length === 0 ? <div className="small text-body-secondary">Not in any group.</div> : (
              <ul className="list-unstyled mb-0">
                {s.groups.map((g) => (
                  <li key={g.id}><Link to={`/servers?groupId=${g.id}`}>{g.name}</Link> <small className="text-body-secondary">({GROUP_TYPE_LABELS[g.groupType]})</small></li>
                ))}
              </ul>
            )}
          </div></div>

          <div className="card"><div className="card-body small">
            <h2 className="h6 mb-2">Record</h2>
            <div>Registered <TimeAgo value={s.createdAt} />{s.createdBy && <> by <strong>{s.createdBy}</strong></>}</div>
            <div>Modified <TimeAgo value={s.updatedAt} />{s.updatedBy && <> by <strong>{s.updatedBy}</strong></>}</div>
          </div></div>
        </div>
      </div>

      <ConfirmDialog show={confirmDelete} title="Delete server?" confirmLabel="Delete server" busy={deleting}
        onConfirm={doDelete} onCancel={() => setConfirmDelete(false)}>
        <p>This removes <strong>{s.name}</strong> from the inventory. The action is recorded in the audit log.</p>
        <p className="mb-0 small text-body-secondary">To keep its history, consider setting the monitoring status to <em>Decommissioned</em> instead.</p>
      </ConfirmDialog>
    </>
  );
}
