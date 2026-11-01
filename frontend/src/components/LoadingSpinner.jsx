import { useI18n } from '../i18n/I18nContext.jsx';

export default function LoadingSpinner({ label, fullPage = false }) {
  const { t } = useI18n();
  const content = (
    <div className="d-flex align-items-center gap-2 text-body-secondary" role="status">
      <div className="spinner-border spinner-border-sm" aria-hidden="true" />
      <span>{label || t('common.loading')}</span>
    </div>
  );
  return fullPage ? <div className="nm-loading-page">{content}</div> : <div className="py-4">{content}</div>;
}
