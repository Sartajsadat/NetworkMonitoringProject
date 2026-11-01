import { errorMessage, errorRequestId } from '../api/client.js';
import { useI18n } from '../i18n/I18nContext.jsx';

/** Shows an API error in the user's language, with the backend request ID for the logs. */
export default function ErrorAlert({ error, onRetry }) {
  const { t } = useI18n();
  if (!error) return null;
  const message = typeof error === 'string' ? error : errorMessage(error, t);
  const requestId = typeof error === 'string' ? null : errorRequestId(error);
  return (
    <div className="alert alert-danger d-flex align-items-start gap-2" role="alert">
      <i className="bi bi-exclamation-octagon-fill mt-1" aria-hidden="true" />
      <div className="flex-grow-1">
        <div>{message}</div>
        {requestId && (
          <small className="text-body-secondary">
            {t('common.requestId')}: <code className="nm-ltr">{requestId}</code>
          </small>
        )}
      </div>
      {onRetry && (
        <button type="button" className="btn btn-sm btn-outline-danger" onClick={onRetry}>
          {t('common.retry')}
        </button>
      )}
    </div>
  );
}
