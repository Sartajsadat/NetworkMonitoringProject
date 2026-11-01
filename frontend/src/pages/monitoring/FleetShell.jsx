import { Link } from 'react-router-dom';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';
import Freshness from '../../components/Freshness.jsx';
import StatusBadge from '../../components/StatusBadge.jsx';

/** Common frame for fleet pages: header, loading/error/empty states. */
export default function FleetShell({ titleKey, subtitleKey, fleet, children, actions }) {
  const { t } = useI18n();
  const { data, error, loading, reload } = fleet;
  return (
    <>
      <PageHeader title={t(titleKey)} subtitle={t(subtitleKey)} breadcrumbs={[{ label: t(titleKey) }]} actions={actions} />
      <ErrorAlert error={error} onRetry={reload} />
      {loading && !data && <LoadingSpinner />}
      {data && data.length === 0 && (
        <div className="card"><EmptyState icon="broadcast" title={t('fleet.empty')}>
          {t('fleet.emptyHint')} <Link to="/agents">{t('nav.agents')}</Link>
        </EmptyState></div>
      )}
      {data && data.length > 0 && children(data)}
    </>
  );
}

/** Server name cell: link to the right tab, connection badge, freshness. */
export function ServerCell({ entry, tab }) {
  return (
    <>
      <Link to={`/servers/${entry.serverId}?tab=${tab}`} className="fw-semibold">{entry.name}</Link>
      <div className="d-flex align-items-center gap-1 flex-wrap mt-1">
        <StatusBadge status={entry.connectionStatus} />
      </div>
    </>
  );
}

export function FreshCell({ entry }) {
  return <Freshness collectedAt={entry.collectedAt} stale={entry.stale} />;
}
