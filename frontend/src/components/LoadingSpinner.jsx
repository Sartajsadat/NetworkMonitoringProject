export default function LoadingSpinner({ label = 'Loading…', fullPage = false }) {
  const content = (
    <div className="d-flex align-items-center gap-2 text-secondary" role="status">
      <div className="spinner-border spinner-border-sm" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
  return fullPage ? <div className="nm-auth-page">{content}</div> : <div className="py-4">{content}</div>;
}
