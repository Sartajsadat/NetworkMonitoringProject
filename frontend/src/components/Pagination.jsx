/** Pager for PageResponse objects ({page, size, totalElements, totalPages}). */
export default function Pagination({ data, onPage }) {
  if (!data || data.totalElements === 0) return null;
  const { page, size, totalElements, totalPages } = data;
  const from = page * size + 1;
  const to = Math.min(totalElements, (page + 1) * size);
  return (
    <div className="d-flex justify-content-between align-items-center flex-wrap gap-2 mt-2">
      <small className="text-body-secondary">
        Showing {from}–{to} of {totalElements}
      </small>
      <div className="btn-group btn-group-sm" role="group" aria-label="Pagination">
        <button type="button" className="btn btn-outline-secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>
          <i className="bi bi-chevron-left" aria-hidden="true" /> Previous
        </button>
        <span className="btn btn-outline-secondary disabled">
          Page {page + 1} of {Math.max(totalPages, 1)}
        </span>
        <button type="button" className="btn btn-outline-secondary" disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>
          Next <i className="bi bi-chevron-right" aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
