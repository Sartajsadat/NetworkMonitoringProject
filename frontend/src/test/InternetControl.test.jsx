import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  internetApi: {
    computers: vi.fn(), view: vi.fn(), addSite: vi.fn(), switchSite: vi.fn(), removeSite: vi.fn(), addApp: vi.fn(), switchApp: vi.fn(),
  },
}));
const { internetApi } = await import('../api/endpoints.js');
const { default: InternetControlPage } = await import('../pages/firewall/InternetControlPage.jsx');

const VIEW = {
  serverId: 's1', serverName: 'my-pc', osFamily: 'WINDOWS', connection: 'ONLINE', personalMode: true,
  sites: { enabledOnComputer: true, message: 'ok', list: [{ id: 'w1', domain: 'youtube.com', blocked: true, state: 'BLOCKED' }] },
  apps: {
    supported: true, identityKind: 'WINDOWS_PROGRAM', enforcing: true,
    list: [{ id: 'a1', name: 'Chrome', identityKind: 'WINDOWS_PROGRAM', identityValue: 'C:\\chrome.exe', internetBlocked: false }],
  },
};

beforeEach(() => {
  vi.clearAllMocks();
  internetApi.computers.mockResolvedValue([{ id: 's1', name: 'my-pc', osFamily: 'WINDOWS', connection: 'ONLINE' }]);
  internetApi.view.mockResolvedValue(VIEW);
});

describe('internet control', () => {
  it('shows websites and programs with their switches', async () => {
    renderWithAuth(<InternetControlPage />, { user: makeUser(['SUPER_ADMIN']) });
    expect(await screen.findByText('youtube.com')).toBeInTheDocument();
    expect(screen.getByText('Blocked')).toBeInTheDocument();
    expect(screen.getByRole('switch', { name: 'Block youtube.com' })).toBeChecked();
    expect(screen.getByRole('switch', { name: 'Internet for Chrome' })).toBeChecked();
    expect(screen.queryByText(/second administrator/)).not.toBeInTheDocument();
  });

  it('blocks a typed website and switches a program off', async () => {
    internetApi.addSite.mockResolvedValue(VIEW);
    internetApi.switchApp.mockResolvedValue({ ...VIEW, apps: { ...VIEW.apps, list: [{ ...VIEW.apps.list[0], internetBlocked: true, status: 'APPROVED' }] } });
    renderWithAuth(<InternetControlPage />, { user: makeUser(['SUPER_ADMIN']) });
    await userEvent.type(await screen.findByLabelText('Website name'), 'facebook.com');
    await userEvent.click(screen.getByRole('button', { name: /^Block$/ }));
    expect(internetApi.addSite).toHaveBeenCalledWith('s1', 'facebook.com');
    await userEvent.click(screen.getByRole('switch', { name: 'Internet for Chrome' }));
    expect(internetApi.switchApp).toHaveBeenCalledWith('a1', false);
    expect(await screen.findByText('Applying…')).toBeInTheDocument();
  });

  it('explains when approval by a second person is still needed', async () => {
    internetApi.view.mockResolvedValue({ ...VIEW, personalMode: false });
    renderWithAuth(<InternetControlPage />, { user: makeUser(['SUPER_ADMIN']) });
    expect(await screen.findByText(/second administrator/)).toBeInTheDocument();
  });
});
