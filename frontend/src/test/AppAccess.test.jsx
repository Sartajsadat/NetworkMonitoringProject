import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  appApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), preview: vi.fn(), request: vi.fn() },
  firewallApi: { rules: vi.fn() },
  serverApi: { search: vi.fn() },
}));
const { appApi, firewallApi, serverApi } = await import('../api/endpoints.js');
const { default: AppAccessPage } = await import('../pages/firewall/AppAccessPage.jsx');
const { default: AppDetailPage } = await import('../pages/firewall/AppDetailPage.jsx');

const APP = {
  id: 'a1', serverId: 's1', serverName: 'app-01', osFamily: 'LINUX', name: 'Payroll API', owner: 'Finance IT', identityKind: 'LINUX_USER',
  identityValue: 'payroll', support: { supported: true, enforcing: true, mode: 'ENFORCE' }, activePolicies: 1, seenProcesses: ['payroll-api (812)'],
};
const UNSUPPORTED = { ...APP, id: 'a2', name: 'Mail relay', identityValue: 'postfix', support: { supported: false, reason: 'This server\'s firewall (UFW) cannot match applications by LINUX_USER' }, activePolicies: 0, seenProcesses: [] };

beforeEach(() => {
  vi.clearAllMocks();
  appApi.list.mockResolvedValue([APP, UNSUPPORTED]);
  serverApi.search.mockResolvedValue({ content: [{ id: 's1', name: 'app-01', osFamily: 'LINUX' }, { id: 's2', name: 'win-01', osFamily: 'WINDOWS' }] });
  firewallApi.rules.mockResolvedValue({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
});

describe('application access', () => {
  it('lists applications with honest support status', async () => {
    renderWithAuth(<AppAccessPage />, { user: makeUser(['READ_ONLY']) });
    expect(await screen.findByRole('link', { name: 'Payroll API' })).toHaveAttribute('href', '/app-access/a1');
    expect(screen.getByText('Enforceable')).toBeInTheDocument();
    expect(screen.getByText('Not supported')).toBeInTheDocument();
    expect(screen.getByText(/cannot match applications by LINUX_USER/)).toBeInTheDocument();
    expect(screen.getByText('Seen running: payroll-api (812)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /register application/i })).not.toBeInTheDocument();
  });

  it('offers only identity types that exist on the server\'s OS', async () => {
    appApi.create.mockResolvedValue({});
    renderWithAuth(<AppAccessPage />, { user: makeUser(['NETWORK_ADMIN']) });
    await userEvent.click(await screen.findByRole('button', { name: /register application/i }));
    await waitFor(() => expect(screen.getByRole('option', { name: 'win-01' })).toBeInTheDocument());
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

  it('requests an allow-only policy after a preview with the application scope', async () => {
    appApi.get.mockResolvedValue(APP);
    appApi.preview.mockResolvedValue({ ok: true, errors: [], warnings: [{ code: 'APP_SHARED_USER', param: 'payroll', text: 'x' }],
      targetName: 'app-01', mode: 'ENFORCE', command: 'nft -f - <<EOF\nadd rule inet netmon output meta skuid "payroll" jump app_<tag>\nEOF' });
    appApi.request.mockResolvedValue({ id: 'r5' });
    renderWithAuth(<AppDetailPage />, { user: makeUser(['SECURITY_ANALYST']), route: '/app-access/a1', path: '/app-access/:id' });
    await userEvent.click(await screen.findByRole('button', { name: 'Request a policy' }));
    await userEvent.click(screen.getByLabelText(/allow only approved destinations/i));
    await userEvent.click(screen.getByRole('button', { name: /add destination/i }));
    await userEvent.type(screen.getByLabelText('Destination address or network'), '10.20.0.15');
    await userEvent.type(screen.getByLabelText('Destination port'), '5432');
    await userEvent.type(screen.getByLabelText('Reason'), 'Payroll must only reach its database');
    await userEvent.click(screen.getByRole('button', { name: 'Check and preview' }));
    expect(await screen.findByText('Applies only to Payroll API on server app-01')).toBeInTheDocument();
    expect(screen.getByText('The policy applies to every process running as payroll, not only this application.')).toBeInTheDocument();
    await userEvent.click(screen.getByLabelText('I have read these warnings'));
    await userEvent.click(screen.getByRole('button', { name: 'Submit for approval' }));
    await waitFor(() => expect(appApi.request).toHaveBeenCalledWith(expect.objectContaining({
      applicationId: 'a1', appMode: 'ALLOW_ONLY', destinations: [{ cidr: '10.20.0.15', protocol: 'TCP', port: '5432' }], acknowledgeWarnings: true,
    })));
  });

  it('does not offer policies where the server cannot enforce them', async () => {
    appApi.get.mockResolvedValue(UNSUPPORTED);
    renderWithAuth(<AppDetailPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/app-access/a2', path: '/app-access/:id' });
    expect(await screen.findAllByText(/cannot match applications by LINUX_USER/)).not.toHaveLength(0);
    expect(screen.queryByRole('button', { name: 'Request a policy' })).not.toBeInTheDocument();
  });
});
