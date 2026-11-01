import { useState } from 'react';
import { Link } from 'react-router-dom';
import { groupApi } from '../api/endpoints.js';
import { useApi } from '../api/useApi.js';
import { useAuth } from '../auth/AuthContext.jsx';
import { PERMISSIONS } from '../auth/roles.js';
import ConfirmDialog from '../components/ConfirmDialog.jsx';
import EmptyState from '../components/EmptyState.jsx';
import ErrorAlert from '../components/ErrorAlert.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import Modal from '../components/Modal.jsx';
import PageHeader from '../components/PageHeader.jsx';
import { GROUP_TYPE_LABELS } from './labels.js';

const EMPTY = { name: '', groupType: 'DEPARTMENT', description: '' };

export default function ServerGroupsPage() {
  const { can } = useAuth();
  const canManage = can(PERMISSIONS.manageServers);
  const { data, error, loading, reload } = useApi(() => groupApi.list(), []);
  const [editing, setEditing] = useState(null); // null | 'new' | group
  const [form, setForm] = useState(EMPTY);
  const [formError, setFormError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [toDelete, setToDelete] = useState(null);

  const open = (g) => {
    setEditing(g || 'new');
    setForm(g ? { name: g.name, groupType: g.groupType, description: g.description || '' } : EMPTY);
    setFormError(null);
  };
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setFormError(null);
    try {
      if (editing === 'new') await groupApi.create(form);
      else await groupApi.update(editing.id, form);
      setEditing(null);
      reload();
    } catch (err) {
      setFormError(err);
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    setBusy(true);
    try {
      await groupApi.remove(toDelete.id);
      setToDelete(null);
      reload();
    } catch (err) {
      setFormError(err);
      setToDelete(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader title="Server groups" subtitle="Group servers by department, environment, application or server role."
        breadcrumbs={[{ label: 'Server groups' }]}
        actions={canManage && <button type="button" className="btn btn-primary btn-sm" onClick={() => open(null)}>
          <i className="bi bi-plus-lg me-1" aria-hidden="true" />New group</button>} />
      <ErrorAlert error={error} onRetry={reload} />
      {!editing && <ErrorAlert error={formError} />}
      {loading && !data && <LoadingSpinner />}
      {data && data.length === 0 && <div className="card"><EmptyState icon="collection" title="No server groups yet" /></div>}
      {data && data.length > 0 && (
        <div className="card table-responsive">
          <table className="table mb-0 nm-table">
            <thead><tr><th>Name</th><th>Type</th><th>Description</th><th className="text-end">Servers</th>{canManage && <th />}</tr></thead>
            <tbody>
              {data.map((g) => (
                <tr key={g.id}>
                  <td>{g.name}</td>
                  <td>{GROUP_TYPE_LABELS[g.groupType]}</td>
                  <td className="small text-body-secondary">{g.description || '—'}</td>
                  <td className="text-end"><Link to={`/servers?groupId=${g.id}`}>{g.memberCount}</Link></td>
                  {canManage && (
                    <td className="text-end text-nowrap">
                      <button type="button" className="btn btn-sm btn-outline-secondary me-1" onClick={() => open(g)} aria-label={`Edit ${g.name}`}>
                        <i className="bi bi-pencil" aria-hidden="true" />
                      </button>
                      <button type="button" className="btn btn-sm btn-outline-danger" onClick={() => setToDelete(g)} aria-label={`Delete ${g.name}`}>
                        <i className="bi bi-trash" aria-hidden="true" />
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <Modal show={Boolean(editing)} title={editing === 'new' ? 'New server group' : 'Edit server group'} onClose={() => setEditing(null)}>
        <form onSubmit={save}>
          <ErrorAlert error={formError} />
          <div className="mb-3">
            <label htmlFor="g-name" className="form-label">Name</label>
            <input id="g-name" className="form-control" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required maxLength={120} />
          </div>
          <div className="mb-3">
            <label htmlFor="g-type" className="form-label">Type</label>
            <select id="g-type" className="form-select" value={form.groupType} onChange={(e) => setForm({ ...form, groupType: e.target.value })}>
              {Object.entries(GROUP_TYPE_LABELS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </div>
          <div className="mb-3">
            <label htmlFor="g-desc" className="form-label">Description</label>
            <textarea id="g-desc" className="form-control" rows={2} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} maxLength={500} />
          </div>
          <div className="d-flex justify-content-end gap-2">
            <button type="button" className="btn btn-outline-secondary" onClick={() => setEditing(null)}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={busy}>Save</button>
          </div>
        </form>
      </Modal>

      <ConfirmDialog show={Boolean(toDelete)} title="Delete group?" confirmLabel="Delete group" busy={busy}
        onConfirm={remove} onCancel={() => setToDelete(null)}>
        Delete <strong>{toDelete?.name}</strong>? Servers in it are not deleted; they are only removed from the group.
      </ConfirmDialog>
    </>
  );
}
