import { Link } from 'react-router-dom';
import EmptyState from '../components/EmptyState.jsx';
import { useI18n } from '../i18n/I18nContext.jsx';

export default function ForbiddenPage() {
  const { t } = useI18n();
  return (
    <EmptyState icon="shield-lock" title={t('forbidden.title')}>
      {t('forbidden.body')} <Link to="/">{t('notFound.back')}</Link>
    </EmptyState>
  );
}
