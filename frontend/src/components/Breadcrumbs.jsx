import { Link } from 'react-router-dom';

/** items: [{ label, to? }] - the last item is the current page. */
export default function Breadcrumbs({ items }) {
  return (
    <nav aria-label="breadcrumb">
      <ol className="breadcrumb small mb-1">
        <li className="breadcrumb-item">
          <Link to="/">Home</Link>
        </li>
        {items.map((item, i) =>
          i === items.length - 1 || !item.to ? (
            <li key={item.label} className="breadcrumb-item active" aria-current="page">
              {item.label}
            </li>
          ) : (
            <li key={item.label} className="breadcrumb-item">
              <Link to={item.to}>{item.label}</Link>
            </li>
          ),
        )}
      </ol>
    </nav>
  );
}
