import { useI18n } from '../i18n/I18nContext.jsx';

/** Pager for PageResponse objects ({page, size, totalElements, totalPages}). */
export default function Pagination({ data, onPage }) {
  const { t, formatNumber } = useI18n();
  if (!data || data.totalElements === 0) return null;
  const { page, size, totalElements, totalPages } = data;
  const from = page * size + 1;
  const to = Math.min(totalElements, (page + 1) * size);
  return (
    <div className="d-flex justify-content-between align-items-center flex-wrap gap-2">
      <small className="text-body-secondary">
        {t('common.showing', { from: formatNumber(from), to: formatNumber(to), total: formatNumber(totalElements) })}
      </small>
      <div className="btn-group btn-group-sm" role="group">
        <button type="button" className="btn btn-outline-secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
          <i className="bi bi-chevron-left nm-flip" aria-hidden="true" /> {t('common.previous')}
        </button>
        <span className="btn btn-outline-secondary disabled">
          {t('common.pageOf', { page: formatNumber(page + 1), pages: formatNumber(Math.max(totalPages, 1)) })}
        </span>
        <button type="button" className="btn btn-outline-secondary" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
          {t('common.next')} <i className="bi bi-chevron-right nm-flip" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
