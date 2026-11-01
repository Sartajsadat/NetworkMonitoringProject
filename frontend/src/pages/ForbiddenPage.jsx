import { Link } from 'react-router-dom';
import EmptyState from '../components/EmptyState.jsx';

export default function ForbiddenPage() {
  return (
    <EmptyState icon="shield-lock" title="You do not have permission to view this page">
      Ask a super administrator if you need access. <Link to="/">Back to the dashboard</Link>
    </EmptyState>
  );
}
