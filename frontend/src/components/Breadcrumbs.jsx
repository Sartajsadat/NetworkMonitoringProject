import { Link } from 'react-router-dom';
import { useI18n } from '../i18n/I18nContext.jsx';

/** items: [{ label, to? }] - the last item is the current page. */
export default function Breadcrumbs({ items }) {
  const { t } = useI18n();
  return (
    <nav aria-label="breadcrumb">
      <ol className="breadcrumb small mb-1">
        <li className="breadcrumb-item"><Link to="/">{t('common.home')}</Link></li>
        {items.map((item, i) =>
          i === items.length - 1 || !item.to ? (
            <li key={item.label} className="breadcrumb-item active" aria-current="page">{item.label}</li>
          ) : (
            <li key={item.label} className="breadcrumb-item"><Link to={item.to}>{item.label}</Link></li>
          ),
        )}
      </ol>
    </nav>
  );
}
