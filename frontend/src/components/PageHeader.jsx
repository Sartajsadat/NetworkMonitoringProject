import Breadcrumbs from './Breadcrumbs.jsx';

export default function PageHeader({ title, subtitle, breadcrumbs, actions }) {
  return (
    <div className="mb-3">
      {breadcrumbs && <Breadcrumbs items={breadcrumbs} />}
      <div className="d-flex justify-content-between align-items-start flex-wrap gap-2">
        <div>
          <h1 className="h4 mb-0">{title}</h1>
          {subtitle && <div className="text-body-secondary small mt-1">{subtitle}</div>}
        </div>
        {actions && <div className="d-flex gap-2 flex-wrap">{actions}</div>}
      </div>
    </div>
  );
}
