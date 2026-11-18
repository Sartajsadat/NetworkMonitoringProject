import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  websiteApi: { overview: vi.fn(), add: vi.fn(), setBlocked: vi.fn(), remove: vi.fn() },
}));
const { websiteApi } = await import('../api/endpoints.js');
const { default: WebsitesPage } = await import('../pages/websites/WebsitesPage.jsx');

const DEVICES = [
  { serverId: 's1', name: 'app-01', osFamily: 'LINUX', enabled: true, online: true, reportedAt: new Date().toISOString() },
  { serverId: 's2', name: 'pc-finance-3', osFamily: 'WINDOWS', enabled: true, online: true, reportedAt: new Date().toISOString() },
  { serverId: 's3', name: 'db-01', osFamily: 'LINUX', enabled: false, online: true },
];
const SITE = {
  id: 'w1', domain: 'facebook.com', names: ['facebook.com', 'www.facebook.com', 'm.facebook.com'], blocked: true, updatedBy: 'neta',
  updatedAt: new Date().toISOString(), summary: { total: 3, done: 1, waiting: 1, offline: 0, notSetUp: 1 },
  devices: [{ serverId: 's1', serverName: 'app-01', state: 'BLOCKED' }, { serverId: 's2', serverName: 'pc-finance-3', state: 'WAITING' },
    { serverId: 's3', serverName: 'db-01', state: 'NOT_SET_UP' }],
};
const OVERVIEW = { sites: [SITE], devices: DEVICES };

beforeEach(() => {
  vi.clearAllMocks();
  websiteApi.overview.mockResolvedValue(OVERVIEW);
});

describe('website blocking', () => {
  it('shows each website with its switch and what the devices confirmed', async () => {
    renderWithAuth(<WebsitesPage />, { user: makeUser(['READ_ONLY']) });
    expect(await screen.findByText('facebook.com')).toBeInTheDocument();
    expect(screen.getByText('also www.facebook.com, m.facebook.com')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Block facebook.com' })).toBeChecked();
    expect(screen.getByRole('switch', { name: 'Block facebook.com' })).toBeDisabled();
    expect(screen.getByText('Blocked on 1 of 2 devices')).toBeInTheDocument();
    expect(screen.getByText('Waiting for 1 device(s)…')).toBeInTheDocument();
    expect(screen.getByText('Website blocking is set up on 2 of 3 devices.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Block' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText('Waiting for the device…')).toBeInTheDocument();
  });

  it('adds a website and switches blocking off and on', async () => {
    websiteApi.add.mockResolvedValue(OVERVIEW);
    websiteApi.setBlocked.mockResolvedValue({ ...OVERVIEW, sites: [{ ...SITE, blocked: false }] });
    renderWithAuth(<WebsitesPage />, { user: makeUser(['NETWORK_ADMIN']) });
    await userEvent.type(await screen.findByLabelText('Website'), 'https://www.youtube.com/watch');
    await userEvent.click(screen.getByRole('button', { name: 'Block' }));
    await waitFor(() => expect(websiteApi.add).toHaveBeenCalledWith('https://www.youtube.com/watch', null));
    await userEvent.click(screen.getByRole('switch', { name: 'Block facebook.com' }));
    await waitFor(() => expect(websiteApi.setBlocked).toHaveBeenCalledWith('w1', false));
    expect(await screen.findByRole('switch', { name: 'Block facebook.com' })).not.toBeChecked();
  });

  it('says clearly when no device is set up yet', async () => {
    websiteApi.overview.mockResolvedValue({ sites: [], devices: [{ ...DEVICES[2] }] });
    renderWithAuth(<WebsitesPage />, { user: makeUser(['NETWORK_ADMIN']) });
    expect(await screen.findByText(/nothing is blocked anywhere/)).toBeInTheDocument();
    expect(screen.getByText('sudo ./deploy/linux/enable-website-blocking.sh')).toBeInTheDocument();
  });
});
