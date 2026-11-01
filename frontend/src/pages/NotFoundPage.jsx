import { Link } from 'react-router-dom';
import EmptyState from '../components/EmptyState.jsx';

export default function NotFoundPage() {
  return (
    <EmptyState icon="signpost-split" title="Page not found">
      <Link to="/">Back to the dashboard</Link>
    </EmptyState>
  );
}
