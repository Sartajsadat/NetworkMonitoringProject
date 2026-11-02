import { useState } from 'react';
import { networkApi } from '../../api/endpoints.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import Modal from '../../components/Modal.jsx';
import { ALLOCATIONS, blankToNull } from './networkText.js';

export default function IpForm({ ip, onSaved, onClose }) {
  const { t } = useI18n();
  const [f, setF] = useState({ address: ip?.address || '', macAddress: ip?.macAddress || '', hostname: ip?.hostname || '',
    allocation: ip?.allocation || 'UNKNOWN', notes: ip?.notes || '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const set = (k) => (e) => setF((x) => ({ ...x, [k]: e.target.value }));
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onSaved(ip ? await networkApi.updateIp(ip.id, blankToNull(f)) : await networkApi.createIp(blankToNull(f)));
    } catch (err) {
      setError(err);
      setBusy(false);
    }
  };
  return (
    <Modal show title={ip ? t('ips.edit') : t('ips.new')} onClose={() => !busy && onClose()}
      footer={(<><button type="button" className="btn btn-outline-secondary" onClick={onClose} disabled={busy}>{t('common.cancel')}</button>
        <button type="submit" form="ip-form" className="btn btn-primary" disabled={busy}>{t('common.save')}</button></>)}>
      <form id="ip-form" onSubmit={save} className="row g-3">
        <div className="col-md-6"><label className="form-label" htmlFor="ip-addr">{t('ips.address')}</label>
          <input id="ip-addr" className="form-control nm-ltr" dir="ltr" required maxLength={45} value={f.address} onChange={set('address')} disabled={Boolean(ip)} /></div>
        <div className="col-md-6"><label className="form-label" htmlFor="ip-alloc">{t('ips.allocation')}</label>
          <select id="ip-alloc" className="form-select" value={f.allocation} onChange={set('allocation')}>
            {ALLOCATIONS.map((a) => <option key={a} value={a}>{t(`allocation.${a}`)}</option>)}</select></div>
        <div className="col-md-6"><label className="form-label" htmlFor="ip-host">{t('ips.hostname')}</label>
          <input id="ip-host" className="form-control nm-ltr" dir="ltr" maxLength={253} value={f.hostname} onChange={set('hostname')} /></div>
        <div className="col-md-6"><label className="form-label" htmlFor="ip-mac">{t('ips.mac')}</label>
          <input id="ip-mac" className="form-control nm-ltr" dir="ltr" maxLength={17} value={f.macAddress} onChange={set('macAddress')} placeholder="aa:bb:cc:dd:ee:ff" /></div>
        <div className="col-12"><label className="form-label" htmlFor="ip-notes">{t('ips.notes')}</label>
          <textarea id="ip-notes" className="form-control" rows={2} maxLength={1000} value={f.notes} onChange={set('notes')} /></div>
        <div className="col-12"><ErrorAlert error={error} /></div>
      </form>
    </Modal>
  );
}
