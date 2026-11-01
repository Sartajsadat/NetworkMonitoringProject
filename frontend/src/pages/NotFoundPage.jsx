import { Link } from 'react-router-dom';
import EmptyState from '../components/EmptyState.jsx';
import { useI18n } from '../i18n/I18nContext.jsx';

export default function NotFoundPage() {
  const { t } = useI18n();
  return (
    <EmptyState icon="signpost-split" title={t('notFound.title')}>
      <Link to="/">{t('notFound.back')}</Link>
    </EmptyState>
  );
}
