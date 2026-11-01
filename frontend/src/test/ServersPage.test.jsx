import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  serverApi: { search: vi.fn(), organizations: vi.fn() },
  groupApi: { list: vi.fn() },
}));

const { serverApi, groupApi } = await import('../api/endpoints.js');
const { default: ServersPage } = await import('../pages/ServersPage.jsx');

const page = (content) => ({ content, page: 0, size: 25, totalElements: content.length, totalPages: 1 });
const server = {
  id: 's1', name: 'web-01', hostname: 'web-01.mot.local', osFamily: 'LINUX', environment: 'PRODUCTION',
  organization: 'Ministry of Health', monitoringStatus: 'PENDING_AGENT', connectionStatus: 'NO_AGENT',
  lastSeenAt: null, ipAddresses: [{ address: '10.0.0.5', version: 4 }], groups: [],
};

describe('ServersPage', () => {
  beforeEach(() => {
    serverApi.search.mockReset().mockResolvedValue(page([server]));
    serverApi.organizations.mockResolvedValue(['Ministry of Health']);
    groupApi.list.mockResolvedValue([]);
  });

  it('lists servers with honest connection status', async () => {
    renderWithAuth(<ServersPage />, { user: makeUser(['READ_ONLY']) });
    expect(await screen.findByText('web-01')).toBeInTheDocument();
    expect(screen.getByText('10.0.0.5')).toBeInTheDocument();
    expect(screen.getByText('No agent')).toBeInTheDocument();
    expect(screen.getByText('Never')).toBeInTheDocument();
  });

  it('hides the register button from read-only users', async () => {
    renderWithAuth(<ServersPage />, { user: makeUser(['READ_ONLY']) });
    await screen.findByText('web-01');
    expect(screen.queryByRole('link', { name: /register server/i })).not.toBeInTheDocument();
  });

  it('shows the register button to infrastructure admins', async () => {
    renderWithAuth(<ServersPage />, { user: makeUser(['INFRASTRUCTURE_ADMIN']) });
    expect(await screen.findByRole('link', { name: /register server/i })).toBeInTheDocument();
  });

  it('sends selected filters to the API', async () => {
    renderWithAuth(<ServersPage />, { user: makeUser(['READ_ONLY']) });
    await screen.findByText('web-01');
    await userEvent.selectOptions(screen.getByLabelText('Environment'), 'PRODUCTION');
    await userEvent.type(screen.getByLabelText('Search'), '10.0.0');
    await userEvent.click(screen.getByRole('button', { name: /apply/i }));
    await waitFor(() => expect(serverApi.search).toHaveBeenLastCalledWith(
      expect.objectContaining({ environment: 'PRODUCTION', search: '10.0.0', page: 0 }),
    ));
  });

  it('shows the error message and request ID when loading fails', async () => {
    serverApi.search.mockReset().mockRejectedValue({ response: { status: 500, data: { message: 'Unexpected server error.', requestId: 'abc-999' } } });
    renderWithAuth(<ServersPage />, { user: makeUser(['READ_ONLY']) });
    expect(await screen.findByText('Unexpected server error.')).toBeInTheDocument();
    expect(screen.getByText('abc-999')).toBeInTheDocument();
  });
});
