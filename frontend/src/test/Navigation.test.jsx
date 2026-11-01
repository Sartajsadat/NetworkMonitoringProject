import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import Sidebar from '../components/Sidebar.jsx';
import ProtectedRoute from '../auth/ProtectedRoute.jsx';
import { makeUser, renderWithAuth } from './utils.jsx';

describe('Sidebar', () => {
  it('hides admin links from read-only users', () => {
    renderWithAuth(<Sidebar />, { user: makeUser(['READ_ONLY']) });
    expect(screen.getByRole('link', { name: /server inventory/i })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /users & roles/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /audit log/i })).not.toBeInTheDocument();
  });

  it('shows admin links to super admins', () => {
    renderWithAuth(<Sidebar />, { user: makeUser(['SUPER_ADMIN']) });
    expect(screen.getByRole('link', { name: /users & roles/i })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /audit log/i })).toBeInTheDocument();
  });

  it('marks unbuilt modules as disabled with their phase instead of linking to them', () => {
    renderWithAuth(<Sidebar />, { user: makeUser(['NETWORK_ADMIN']) });
    expect(screen.queryByRole('link', { name: /packet analyzer/i })).not.toBeInTheDocument();
    const item = screen.getByText('Packet analyzer').closest('[aria-disabled="true"]');
    expect(item).toHaveTextContent('Phase 4');
  });
});

describe('ProtectedRoute', () => {
  it('redirects anonymous users to login', () => {
    renderWithAuth(<ProtectedRoute><div>SECRET</div></ProtectedRoute>, { user: null });
    expect(screen.getByText('LOGIN PAGE')).toBeInTheDocument();
    expect(screen.queryByText('SECRET')).not.toBeInTheDocument();
  });

  it('shows a forbidden message when the role is missing', () => {
    renderWithAuth(<ProtectedRoute roles={['SUPER_ADMIN']}><div>SECRET</div></ProtectedRoute>, { user: makeUser(['READ_ONLY']) });
    expect(screen.getByText(/do not have permission/i)).toBeInTheDocument();
    expect(screen.queryByText('SECRET')).not.toBeInTheDocument();
  });

  it('renders children for an allowed role', () => {
    renderWithAuth(<ProtectedRoute roles={['SUPER_ADMIN']}><div>SECRET</div></ProtectedRoute>, { user: makeUser(['SUPER_ADMIN']) });
    expect(screen.getByText('SECRET')).toBeInTheDocument();
  });
});
