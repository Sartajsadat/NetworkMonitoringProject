import { useEffect, useState } from 'react';
import { captureApi } from '../../api/endpoints.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';

/** One protocol layer as a collapsible list (TShark's own labels, so it stays left-to-right). */
function Layer({ node, depth, open }) {
  if (!node.children || node.children.length === 0) {
    return <li className="nm-tree-leaf">{node.label}</li>;
  }
  return (
    <li>
      <details open={open}>
        <summary>{node.label}</summary>
        <ul className="nm-tree">
          {node.children.map((c, i) => <Layer key={i} node={c} depth={depth + 1} open={false} />)}
        </ul>
      </details>
    </li>
  );
}

export default function PacketDetail({ captureId, frame, canSeeBytes, onClose }) {
  const { t } = useI18n();
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState(null);
  const [bytes, setBytes] = useState(null);
  const [bytesError, setBytesError] = useState(null);
  const [bytesBusy, setBytesBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setDetail(null);
    setError(null);
    setBytes(null);
    setBytesError(null);
    captureApi.packet(captureId, frame).then((d) => !cancelled && setDetail(d)).catch((e) => !cancelled && setError(e));
    return () => { cancelled = true; };
  }, [captureId, frame]);

  const loadBytes = async () => {
    setBytesBusy(true);
    try {
      setBytes(await captureApi.bytes(captureId, frame));
    } catch (e) {
      setBytesError(e);
    } finally {
      setBytesBusy(false);
    }
  };

  return (
    <div className="card mt-3" aria-live="polite">
      <div className="card-header d-flex justify-content-between align-items-center gap-2">
        <h2 className="h6 mb-0">{t('analyzer.packetTitle', { n: frame })}</h2>
        <button type="button" className="btn-close" aria-label={t('common.close')} onClick={onClose} />
      </div>
      <div className="card-body">
        <ErrorAlert error={error} />
        {!detail && !error && <LoadingSpinner />}
        {detail && (
          <div className="row g-3">
            <div className={canSeeBytes ? 'col-xxl-6' : 'col-12'}>
              <div className="small text-body-secondary mb-1">{t('analyzer.layers')}</div>
              <ul className="nm-tree nm-tree-root nm-mono small" dir="ltr">
                {detail.layers.map((l, i) => <Layer key={i} node={l} depth={0} open={i === detail.layers.length - 1} />)}
              </ul>
            </div>
            {canSeeBytes && (
              <div className="col-xxl-6">
                <div className="small text-body-secondary mb-1">{t('analyzer.bytes')}</div>
                {!bytes && (
                  <>
                    <p className="small mb-2">{t('analyzer.bytesWarning')}</p>
                    <button type="button" className="btn btn-sm btn-outline-secondary" onClick={loadBytes} disabled={bytesBusy}>
                      <i className="bi bi-file-binary me-1" aria-hidden="true" />{t('analyzer.showBytes')}
                    </button>
                  </>
                )}
                <ErrorAlert error={bytesError} />
                {bytes && <pre className="nm-hex nm-mono small mb-0" dir="ltr">{bytes.hex}</pre>}
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
