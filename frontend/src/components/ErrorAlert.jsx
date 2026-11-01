import { errorMessage, errorRequestId } from '../api/client.js';

/** Shows an API error with the backend request ID so operators can find it in the logs. */
export default function ErrorAlert({ error, onRetry }) {
  if (!error) return null;
  const message = typeof error === 'string' ? error : errorMessage(error);
  const requestId = typeof error === 'string' ? null : errorRequestId(error);
  return (
    <div className="alert alert-danger d-flex align-items-start gap-2" role="alert">
      <i className="bi bi-exclamation-octagon-fill mt-1" aria-hidden="true" />
      <div className="flex-grow-1">
        <div>{message}</div>
        {requestId && <small className="text-body-secondary">Request ID: <code>{requestId}</code></small>}
      </div>
      {onRetry && (
        <button type="button" className="btn btn-sm btn-outline-danger" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  );
}
