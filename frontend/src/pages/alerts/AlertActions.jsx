import { useState } from 'react';
import { alertApi } from '../../api/endpoints.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import Modal from '../../components/Modal.jsx';

/** Acknowledge / resolve buttons with their comment dialogs. */
export default function AlertActions({ alert, onChanged, size = 'sm' }) {
  const { t } = useI18n();
  const [mode, setMode] = useState(null); // 'ack' | 'resolve'
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  if (alert.status === 'RESOLVED') return null;
  const open = (m) => { setMode(m); setComment(''); setError(null); };
  const submit = async () => {
    setBusy(true);
    setError(null);
    try {
      const res = mode === 'ack' ? await alertApi.acknowledge(alert.id, comment.trim()) : await alertApi.resolve(alert.id, comment.trim());
      setMode(null);
      onChanged?.(res);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <>
      <div className="d-flex gap-1 flex-wrap">
        {alert.status === 'OPEN' && (
          <button type="button" className={`btn btn-${size} btn-outline-primary`} onClick={() => open('ack')}>
            <i className="bi bi-check2 me-1" aria-hidden="true" />{t('alert.acknowledge')}
          </button>
        )}
        <button type="button" className={`btn btn-${size} btn-outline-success`} onClick={() => open('resolve')}>
          <i className="bi bi-check2-all me-1" aria-hidden="true" />{t('alert.resolve')}
        </button>
      </div>
      <Modal show={mode !== null} onClose={() => !busy && setMode(null)}
        title={mode === 'ack' ? t('alert.ackTitle') : t('alert.resolveTitle')}
        footer={(
          <>
            <button type="button" className="btn btn-outline-secondary" onClick={() => setMode(null)} disabled={busy}>{t('common.cancel')}</button>
            <button type="button" className="btn btn-primary" onClick={submit} disabled={busy || (mode === 'resolve' && comment.trim().length < 3)}>
              {mode === 'ack' ? t('alert.acknowledge') : t('alert.resolve')}
            </button>
          </>
        )}>
        <p className="small">{mode === 'ack' ? t('alert.ackText') : t('alert.resolveText')}</p>
        <label className="form-label" htmlFor="alert-comment">{mode === 'ack' ? t('alert.commentOptional') : t('alert.resolution')}</label>
        <textarea id="alert-comment" className="form-control" rows={3} maxLength={1000} value={comment}
          onChange={(e) => setComment(e.target.value)} />
        <ErrorAlert error={error} />
      </Modal>
    </>
  );
}
