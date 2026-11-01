import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext.jsx';
import LoadingSpinner from '../components/LoadingSpinner.jsx';
import ForbiddenPage from '../pages/ForbiddenPage.jsx';

/** Requires a signed-in user and, optionally, one of `roles`. */
export default function ProtectedRoute({ roles, children }) {
  const { user, initializing, setupRequired, can } = useAuth();
  const location = useLocation();

  if (initializing) return <LoadingSpinner label="Checking session…" fullPage />;
  if (setupRequired) return <Navigate to="/setup" replace />;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (roles && !can(roles)) return <ForbiddenPage />;
  return children;
}
