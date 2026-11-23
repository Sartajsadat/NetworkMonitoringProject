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

  it('leaves hidden items and sections with only hidden items out of the sidebar', () => {
    renderWithAuth(<Sidebar />, { user: makeUser(['SUPER_ADMIN']) });
    expect(screen.getByRole('link', { name: /internet control/i })).toHaveAttribute('href', '/internet');
    for (const name of [/firewall integrations/i, /ip allow \/ block/i, /application access/i, /packet analyzer/i, /^captures/i,
      /traffic/i, /ip addresses/i, /subnets/i, /network devices/i, /server groups/i, /^Network\ interfaces$/i]) {
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument();
    }
    expect(screen.queryByText('Applications')).not.toBeInTheDocument();
    expect(screen.queryByText('Logs')).not.toBeInTheDocument();
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
