import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import LoginPage from '../pages/LoginPage.jsx';
import { renderWithAuth } from './utils.jsx';

describe('LoginPage', () => {
  it('submits credentials and shows the server error message on failure', async () => {
    const login = vi.fn().mockRejectedValue({
      response: { status: 401, data: { message: 'Invalid username or password, or the account is locked or disabled.', requestId: 'req-123' } },
    });
    renderWithAuth(<LoginPage />, { user: null, path: '/login', route: '/login', auth: { login } });

    const button = screen.getByRole('button', { name: /sign in/i });
    expect(button).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/username/i), 'alice');
    await userEvent.type(screen.getByLabelText(/password/i), 'wrong-password');
    await userEvent.click(button);

    expect(login).toHaveBeenCalledWith('alice', 'wrong-password');
    expect(await screen.findByRole('alert')).toHaveTextContent(/invalid username or password/i);
    expect(screen.getByText('req-123')).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toHaveValue('');
  });

  it('redirects to first-run setup when no administrator exists', async () => {
    renderWithAuth(<LoginPage />, { user: null, path: '/login', route: '/login', auth: { setupRequired: true } });
    await waitFor(() => expect(screen.getByText('SETUP PAGE')).toBeInTheDocument());
  });

  it('shows a session-expired notice', () => {
    renderWithAuth(<LoginPage />, { user: null, path: '/login', route: '/login', auth: { sessionExpired: true } });
    expect(screen.getByText(/session expired/i)).toBeInTheDocument();
  });
});
