import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import UsageBar from '../../components/UsageBar.jsx';

export default function StorageTab({ latest }) {
  const { t, units } = useI18n();
  const disks = latest?.report?.disks || [];
  const io = latest?.report?.diskIo || [];
  if (disks.length === 0 && io.length === 0) return <div className="card"><EmptyState icon="device-hdd" title={t('chart.noData')} /></div>;
  return (
    <div className="row g-3">
      <div className="col-12">
        <div className="card">
          <div className="card-header"><h2 className="h6 mb-0">{t('storage.filesystems')}</h2></div>
          <div className="table-responsive">
            <table className="table table-sm nm-table">
              <thead><tr><th>{t('storage.col.mount')}</th><th>{t('storage.col.device')}</th><th>{t('storage.col.type')}</th>
                <th>{t('storage.col.used')}</th><th className="text-end">{t('storage.col.usedBytes')}</th><th className="text-end">{t('storage.col.free')}</th><th className="text-end">{t('storage.col.total')}</th></tr></thead>
              <tbody>
                {disks.map((d) => (
                  <tr key={d.mount}>
                    <td className="nm-mono"><span className="nm-ltr">{d.mount}</span></td>
                    <td className="nm-mono small"><span className="nm-ltr">{d.device}</span></td>
                    <td className="small nm-ltr">{d.fsType}</td>
                    <td><UsageBar value={d.usedPercent} /></td>
                    <td className="text-end nm-num">{units.bytes(d.usedBytes)}</td>
                    <td className="text-end nm-num">{units.bytes(d.freeBytes)}</td>
                    <td className="text-end nm-num">{units.bytes(d.totalBytes)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <div className="col-12">
        <div className="card">
          <div className="card-header"><h2 className="h6 mb-0">{t('storage.io')}</h2></div>
          <div className="table-responsive">
            <table className="table table-sm nm-table">
              <thead><tr><th>{t('storage.col.device')}</th><th className="text-end">{t('metric.read')}</th><th className="text-end">{t('metric.write')}</th>
                <th className="text-end">{t('storage.col.ops')}</th><th>{t('storage.col.busy')}</th></tr></thead>
              <tbody>
                {io.map((d) => (
                  <tr key={d.device}>
                    <td className="nm-mono"><span className="nm-ltr">{d.device}</span></td>
                    <td className="text-end nm-num">{units.bytesPerSec(d.readBytesPerSec)}</td>
                    <td className="text-end nm-num">{units.bytesPerSec(d.writeBytesPerSec)}</td>
                    <td className="text-end nm-num small">{units.perSec(d.readOpsPerSec)} / {units.perSec(d.writeOpsPerSec)}</td>
                    <td><UsageBar value={d.busyPercent} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}
