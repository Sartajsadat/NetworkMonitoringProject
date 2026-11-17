import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  appApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), setInternet: vi.fn() },
  firewallApi: { rules: vi.fn() },
  serverApi: { search: vi.fn() },
}));
const { appApi, firewallApi, serverApi } = await import('../api/endpoints.js');
const { default: AppAccessPage } = await import('../pages/firewall/AppAccessPage.jsx');
const { default: AppDetailPage } = await import('../pages/firewall/AppDetailPage.jsx');

const APP = {
  id: 'a1', serverId: 's1', serverName: 'app-01', osFamily: 'LINUX', name: 'Payroll API', owner: 'Finance IT', identityKind: 'LINUX_USER',
  identityValue: 'payroll', support: { supported: true, enforcing: true, mode: 'ENFORCE' }, activePolicies: 0, internet: { state: 'ON' }, seenProcesses: ['payroll-api (812)'],
};
const UNSUPPORTED = { ...APP, id: 'a2', name: 'Mail relay', identityValue: 'postfix', support: { supported: false, reason: 'This server\'s firewall (UFW) cannot match applications by LINUX_USER' }, activePolicies: 0, seenProcesses: [] };

beforeEach(() => {
  vi.clearAllMocks();
  appApi.list.mockResolvedValue([APP, UNSUPPORTED]);
  serverApi.search.mockResolvedValue({ content: [{ id: 's1', name: 'app-01', osFamily: 'LINUX' }, { id: 's2', name: 'win-01', osFamily: 'WINDOWS' }] });
  firewallApi.rules.mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
});

describe('application internet access', () => {
  it('lists applications with a switch and the server\'s honest state', async () => {
    appApi.list.mockResolvedValue([APP, { ...UNSUPPORTED }, { ...APP, id: 'a3', name: 'Reports', internet: { state: 'OFF' } },
      { ...APP, id: 'a4', name: 'Backup', support: { supported: true, enforcing: false }, internet: { state: 'OFF_TEST' } }]);
    renderWithAuth(<AppAccessPage />, { user: makeUser(['READ_ONLY']) });
    expect(await screen.findByRole('link', { name: 'Payroll API' })).toHaveAttribute('href', '/app-access/a1');
    expect(screen.getByRole('switch', { name: 'Internet access for Payroll API' })).toBeChecked();
    expect(screen.getByRole('switch', { name: 'Internet access for Reports' })).not.toBeChecked();
    expect(screen.getByText('Blocked')).toBeInTheDocument();
    expect(screen.getByText('Test mode – not really blocked')).toBeInTheDocument();
    expect(screen.getByText(/some servers are in test mode/i)).toBeInTheDocument();
    expect(screen.getByText(/cannot match applications by LINUX_USER/)).toBeInTheDocument();
    // Read-only users see the switches but cannot use them.
    expect(screen.getByRole('switch', { name: 'Internet access for Payroll API' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /add application/i })).not.toBeInTheDocument();
  });

  it('turns internet off after a confirmation, and back on directly', async () => {
    appApi.list.mockResolvedValue([APP, { ...APP, id: 'a3', name: 'Reports', internet: { state: 'OFF' } }]);
    appApi.setInternet.mockResolvedValue({});
    renderWithAuth(<AppAccessPage />, { user: makeUser(['NETWORK_ADMIN']) });
    await userEvent.click(await screen.findByRole('switch', { name: 'Internet access for Payroll API' }));
    expect(screen.getByText(/Payroll API on server app-01 will no longer reach the internet/)).toBeInTheDocument();
    expect(appApi.setInternet).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Turn off' }));
    await waitFor(() => expect(appApi.setInternet).toHaveBeenCalledWith('a1', false));
    await userEvent.click(screen.getByRole('switch', { name: 'Internet access for Reports' }));
    await waitFor(() => expect(appApi.setInternet).toHaveBeenCalledWith('a3', true));
  });

  it('cannot turn internet off where the server cannot enforce it', async () => {
    appApi.list.mockResolvedValue([UNSUPPORTED]);
    renderWithAuth(<AppAccessPage />, { user: makeUser(['NETWORK_ADMIN']) });
    expect(await screen.findByRole('switch', { name: 'Internet access for Mail relay' })).toBeDisabled();
  });

  it('picks the only identity type itself and offers only types that exist on the server\'s OS', async () => {
    appApi.create.mockResolvedValue({});
    renderWithAuth(<AppAccessPage />, { user: makeUser(['NETWORK_ADMIN']) });
    await userEvent.click(await screen.findByRole('button', { name: /add application/i }));
    await waitFor(() => expect(screen.getByRole('option', { name: 'win-01' })).toBeInTheDocument());
    await userEvent.selectOptions(screen.getByLabelText('Server'), 's1');
    expect(screen.getByLabelText('Identity type')).toHaveValue('LINUX_USER');
    await userEvent.selectOptions(screen.getByLabelText('Server'), 's2');
    const kinds = screen.getByLabelText('Identity type');
    expect(kinds).toHaveTextContent('Windows program');
    expect(kinds).not.toHaveTextContent('Linux account');
    await userEvent.selectOptions(kinds, 'WINDOWS_SERVICE');
    await userEvent.type(screen.getByLabelText('Name'), 'IIS');
    await userEvent.type(screen.getByLabelText('Identity'), 'W3SVC');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(appApi.create).toHaveBeenCalledWith(expect.objectContaining({ serverId: 's2', identityKind: 'WINDOWS_SERVICE', identityValue: 'W3SVC' })));
  });

  it('detail page shows the switch and the history instead of a policy form', async () => {
    appApi.get.mockResolvedValue({ ...APP, internet: { state: 'FAILED', message: 'nft: User does not exist' } });
    firewallApi.rules.mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
    renderWithAuth(<AppDetailPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/app-access/a1', path: '/app-access/:id' });
    expect(await screen.findByRole('switch', { name: 'Internet access for Payroll API' })).not.toBeChecked();
    expect(screen.getByText('nft: User does not exist')).toBeInTheDocument();
    expect(screen.getByText('History')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Request a policy' })).not.toBeInTheDocument();
  });
});
