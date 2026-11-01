import { useEffect, useRef } from 'react';
import { useI18n } from '../i18n/I18nContext.jsx';

/** Accessible confirmation modal rendered with Bootstrap classes (no Bootstrap JS needed). */
export default function ConfirmDialog({
  show, title, children, confirmLabel, confirmVariant = 'danger', busy = false, onConfirm, onCancel,
}) {
  const { t } = useI18n();
  const cancelRef = useRef(null);
  useEffect(() => {
    if (show) cancelRef.current?.focus();
  }, [show]);
  useEffect(() => {
    if (!show) return undefined;
    const onKey = (e) => e.key === 'Escape' && !busy && onCancel();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [show, busy, onCancel]);
  if (!show) return null;
  return (
    <>
      <div className="modal d-block" tabIndex="-1" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <div className="modal-dialog modal-dialog-centered">
          <div className="modal-content">
            <div className="modal-header">
              <h5 className="modal-title" id="confirm-title">{title}</h5>
            </div>
            <div className="modal-body">{children}</div>
            <div className="modal-footer">
              <button ref={cancelRef} type="button" className="btn btn-outline-secondary" onClick={onCancel} disabled={busy}>
                {t('common.cancel')}
              </button>
              <button type="button" className={`btn btn-${confirmVariant}`} onClick={onConfirm} disabled={busy}>
                {busy && <span className="spinner-border spinner-border-sm me-1" aria-hidden="true" />}
                {confirmLabel}
              </button>
            </div>
          </div>
        </div>
      </div>
      <div className="modal-backdrop show" />
    </>
  );
}
