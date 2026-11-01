import { useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { captureApi } from '../../api/endpoints.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { PERMISSIONS } from '../../auth/roles.js';
import { useI18n } from '../../i18n/I18nContext.jsx';
import EmptyState from '../../components/EmptyState.jsx';
import ErrorAlert from '../../components/ErrorAlert.jsx';
import LoadingSpinner from '../../components/LoadingSpinner.jsx';
import PageHeader from '../../components/PageHeader.jsx';

/** "Packet analyzer" opens the most recent capture that has packets; other captures are picked from the list. */
export default function AnalyzerPage() {
  const { t } = useI18n();
  const { can } = useAuth();
  const [target, setTarget] = useState(undefined);
  const [error, setError] = useState(null);
  useEffect(() => {
    captureApi.list({ page: 0, size: 50 })
      .then((d) => setTarget(d.content.find((c) => c.filesAvailable && c.segmentsCount > 0)?.id ?? null))
      .catch(setError);
  }, []);
  if (error) return <ErrorAlert error={error} />;
  if (target === undefined) return <LoadingSpinner />;
  if (target) return <Navigate to={`/captures/${target}`} replace />;
  return (
    <>
      <PageHeader title={t('nav.packetAnalyzer')} breadcrumbs={[{ label: t('nav.packetAnalyzer') }]} />
      <div className="card">
        <EmptyState icon="diagram-3" title={t('analyzer.emptyTitle')}>
          {t('analyzer.emptyText')}{' '}
          {can(PERMISSIONS.manageCaptures) && <Link to="/captures/new">{t('captures.new')}</Link>}
        </EmptyState>
      </div>
    </>
  );
}
