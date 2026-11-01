export default function EmptyState({ icon = 'inbox', title, children }) {
  return (
    <div className="text-center text-body-secondary py-5">
      <i className={`bi bi-${icon} fs-1 d-block mb-2`} aria-hidden="true" />
      <div className="fw-semibold text-body">{title}</div>
      {children && <div className="mt-1 small">{children}</div>}
    </div>
  );
}
