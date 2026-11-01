import { Link } from 'react-router-dom';
import { useI18n } from '../../i18n/I18nContext.jsx';
import Freshness from '../../components/Freshness.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';
import TimeAgo from '../../components/TimeAgo.jsx';
import UsageBar from '../../components/UsageBar.jsx';

function Field({ label, children }) {
  return (
    <div className="col-sm-6 col-lg-4 mb-3">
      <div className="small text-body-secondary">{label}</div>
      <div>{children ?? <span className="text-body-secondary">—</span>}</div>
    </div>
  );
}

function Tile({ label, children, hint }) {
  return (
    <div className="col-6 col-xl-3">
      <div className="card h-100 nm-stat"><div className="card-body">
        <div className="nm-stat-label mb-1">{label}</div>
        {children}
        {hint && <div className="small text-body-secondary mt-1">{hint}</div>}
      </div></div>
    </div>
  );
}

export default function OverviewTab({ server: s, latest }) {
  const { t, units, formatDuration } = useI18n();
  const r = latest?.available ? latest.report : null;
  const maxDisk = r?.disks?.reduce((m, d) => (d.usedPercent > (m?.usedPercent ?? -1) ? d : m), null);
  const nics = (r?.network || []).filter((n) => n.name !== 'lo');
  const rx = nics.some((n) => n.rxBitsPerSec != null) ? nics.reduce((a, n) => a + (n.rxBitsPerSec || 0), 0) : null;
  const tx = nics.some((n) => n.txBitsPerSec != null) ? nics.reduce((a, n) => a + (n.txBitsPerSec || 0), 0) : null;

  return (
    <div className="row g-3">
      <div className="col-12">
        <div className="d-flex justify-content-between align-items-center flex-wrap gap-2 mb-2">
          <h2 className="h6 mb-0">{t('serverDetail.liveTitle')}</h2>
          <Freshness collectedAt={latest?.collectedAt} stale={latest?.stale} />
        </div>
        {!r ? (
          <div className="card card-body small text-body-secondary">
            <div className="mb-2"><StatusBadge status="NOT_COLLECTED" /></div>
            {latest?.reason === 'NO_AGENT' ? t('serverDetail.noAgentYet') : t('serverDetail.noDataYet')}{' '}
            <Link to="?tab=agent">{t('serverDetail.goToAgent')}</Link>
          </div>
        ) : (
          <div className="row g-3">
            <Tile label={t('metric.cpu')} hint={r.cpu ? `${t('metric.load')} ${units.decimal(r.cpu.load1)} · ${units.decimal(r.cpu.load5)} · ${units.decimal(r.cpu.load15)}` : null}>
              <UsageBar value={r.cpu?.usagePercent} />
            </Tile>
            <Tile label={t('metric.memory')} hint={r.memory ? `${units.bytes(r.memory.usedBytes)} / ${units.bytes(r.memory.totalBytes)}` : null}>
              <UsageBar value={r.memory?.usedPercent} />
            </Tile>
            <Tile label={t('metric.diskFullest')} hint={maxDisk ? <span className="nm-ltr">{maxDisk.mount}</span> : null}>
              <UsageBar value={maxDisk?.usedPercent} />
            </Tile>
            <Tile label={t('metric.throughput')} hint={t('metric.excludesLoopback')}>
              <div className="nm-num"><i className="bi bi-arrow-down text-body-secondary" aria-label={t('metric.in')} /> {units.bits(rx)}</div>
              <div className="nm-num"><i className="bi bi-arrow-up text-body-secondary" aria-label={t('metric.out')} /> {units.bits(tx)}</div>
            </Tile>
          </div>
        )}
      </div>

      <div className="col-lg-8">
        <div className="card"><div className="card-body">
          <h2 className="h6 mb-3">{t('serverDetail.details')}</h2>
          <div className="row">
            <Field label={t('serverDetail.os')}>{t(`os.${s.osFamily}`)}{s.osVersion && <> · <span className="nm-ltr">{s.osVersion}</span></>}</Field>
            <Field label={t('serverDetail.environment')}>{t(`env.${s.environment}`)}</Field>
            <Field label={t('serverDetail.organization')}>{s.organization}</Field>
            <Field label={t('serverDetail.purpose')}>{s.purpose}</Field>
            <Field label={t('serverDetail.location')}>{s.location}</Field>
            <Field label={t('serverDetail.monitoringStatus')}><StatusBadge status={s.monitoringStatus} /></Field>
            {r?.host && <Field label={t('serverDetail.reportedOs')}><span className="nm-ltr">{[r.host.osName, r.host.osVersion].filter(Boolean).join(' ')}</span></Field>}
            {r?.host && <Field label={t('serverDetail.kernel')}><span className="nm-ltr">{r.host.kernel}</span></Field>}
            {r?.host && <Field label={t('serverDetail.uptime')}>{formatDuration(r.host.uptimeSeconds)}</Field>}
          </div>
          {s.notes && <><div className="small text-body-secondary">{t('serverDetail.notes')}</div><div style={{ whiteSpace: 'pre-wrap' }}>{s.notes}</div></>}
        </div></div>
      </div>

      <div className="col-lg-4">
        <div className="card mb-3"><div className="card-body">
          <h2 className="h6 mb-2">{t('serverDetail.ips')}</h2>
          {s.ipAddresses.length === 0 ? <div className="small text-body-secondary">{t('serverDetail.noIps')}</div> : (
            <ul className="list-unstyled mb-0 nm-mono small">
              {s.ipAddresses.map((ip) => <li key={ip.address}><span className="nm-ltr">{ip.address}</span>{ip.label && <span className="text-body-secondary"> · {ip.label}</span>}</li>)}
            </ul>
          )}
        </div></div>
        <div className="card mb-3"><div className="card-body">
          <h2 className="h6 mb-2">{t('serverDetail.groups')}</h2>
          {s.groups.length === 0 ? <div className="small text-body-secondary">{t('serverDetail.noGroups')}</div> : (
            <ul className="list-unstyled mb-0">
              {s.groups.map((g) => <li key={g.id}><Link to={`/servers?groupId=${g.id}`}>{g.name}</Link> <small className="text-body-secondary">({t(`groupType.${g.groupType}`)})</small></li>)}
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
  );
}
