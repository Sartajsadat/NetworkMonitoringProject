import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  firewallApi: {
    integrations: vi.fn(), integration: vi.fn(), createIntegration: vi.fn(), setMode: vi.fn(), deleteIntegration: vi.fn(), rules: vi.fn(),
    rule: vi.fn(), preview: vi.fn(), request: vi.fn(), approve: vi.fn(), reject: vi.fn(), cancel: vi.fn(), remove: vi.fn(),
  },
  serverApi: { search: vi.fn() },
}));
const { firewallApi, serverApi } = await import('../api/endpoints.js');
const { default: FirewallIntegrationsPage } = await import('../pages/firewall/FirewallIntegrationsPage.jsx');
const { default: FirewallRulesPage } = await import('../pages/firewall/FirewallRulesPage.jsx');
const { default: RuleRequestPage } = await import('../pages/firewall/RuleRequestPage.jsx');
const { default: RuleDetailPage } = await import('../pages/firewall/RuleDetailPage.jsx');

const TARGET = {
  id: 'f1', name: 'Host firewall: web-01', kind: 'SERVER_AGENT', serverId: 's1', serverName: 'web-01', mode: 'DRY_RUN', activeRules: 2,
  enforcing: false, target: { agentFirewallEnabled: true, enforcementAllowedOnServer: false, backend: 'NFTABLES', message: 'nftables: NetMon rules in table inet netmon; dry run only', reportedAt: new Date().toISOString(), online: true },
};
const CENTRAL = { id: 'f2', name: 'Core firewall', kind: 'CENTRAL_DRY_RUN', mode: 'DRY_RUN', activeRules: 0, enforcing: false };
const RULE = {
  id: 'r1', integrationId: 'f1', integrationName: 'Host firewall: web-01', integrationKind: 'SERVER_AGENT', serverId: 's1', serverName: 'web-01',
  tag: 'ab12cd34', action: 'BLOCK', direction: 'INBOUND', remoteCidr: '203.0.113.0/24', protocol: 'TCP', localPort: '443',
  reason: 'Brute force against the web login', status: 'REQUESTED', preview: 'nft add rule inet netmon input ip saddr 203.0.113.0/24 tcp dport 443 counter drop comment "netmon:ab12cd34"',
  warnings: [], requestedBy: 'ana', requestedAt: '2026-11-02T10:00:00Z', expiresAt: '2026-11-03T10:00:00Z', expiring: false,
};
const page = (content) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });

beforeEach(() => {
  vi.clearAllMocks();
  firewallApi.integrations.mockResolvedValue([TARGET, CENTRAL]);
  serverApi.search.mockResolvedValue(page([{ id: 's1', name: 'web-01' }, { id: 's2', name: 'db-01' }]));
});

describe('firewall targets', () => {
  it('shows scope and mode; enforcement needs a super admin and the server\'s consent', async () => {
    renderWithAuth(<FirewallIntegrationsPage />, { user: makeUser(['NETWORK_ADMIN']) });
    expect(await screen.findByRole('link', { name: 'web-01' })).toHaveAttribute('href', '/servers/s1');
    expect(screen.getByText('This server only')).toBeInTheDocument();
    expect(screen.getByText('Review only – not applied')).toBeInTheDocument();
    expect(screen.getByText('Server allows dry run only', { exact: false })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Enforce…' }));
    expect(screen.getByText('Only a super administrator can turn on enforcement.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'Enforce…' }).at(-1)).toBeDisabled();
  });

  it('adds a server target (servers already added are disabled)', async () => {
    firewallApi.createIntegration.mockResolvedValue({});
    renderWithAuth(<FirewallIntegrationsPage />, { user: makeUser(['NETWORK_ADMIN']) });
    await userEvent.click(await screen.findByRole('button', { name: /add firewall target/i }));
    await waitFor(() => expect(screen.getByRole('option', { name: /web-01 \(already added\)/ })).toBeDisabled());
    await userEvent.selectOptions(screen.getByLabelText('Server'), 's2');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(firewallApi.createIntegration).toHaveBeenCalledWith({ serverId: 's2', description: null }));
  });

  it('read-only users see targets but no actions', async () => {
    renderWithAuth(<FirewallIntegrationsPage />, { user: makeUser(['READ_ONLY']) });
    await screen.findByRole('link', { name: 'web-01' });
    expect(screen.queryByRole('button', { name: /add firewall target/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Enforce…' })).not.toBeInTheDocument();
  });
});

describe('rules', () => {
  it('lists pending rules with the brief\'s status terms', async () => {
    firewallApi.rules.mockResolvedValue(page([RULE, { ...RULE, id: 'r2', status: 'UNVERIFIED' }]));
    renderWithAuth(<FirewallRulesPage />, { user: makeUser(['SECURITY_ANALYST']) });
    expect(await screen.findByText('Pending approval')).toBeInTheDocument();
    expect(screen.getByText('Unverified')).toBeInTheDocument();
    expect(firewallApi.rules).toHaveBeenCalledWith(expect.objectContaining({ status: ['REQUESTED', 'APPROVED', 'REMOVAL_PENDING'] }));
    expect(screen.getByRole('link', { name: /request a rule/i })).toHaveAttribute('href', '/ip-policies/new');
  });

  it('requires a preview, shows the scope and command, and needs warnings acknowledged before submitting', async () => {
    firewallApi.preview.mockResolvedValue({
      ok: true, errors: [], warnings: [{ code: 'DRY_RUN', text: 'Dry run' }, { code: 'OWN_ADDRESS', param: '203.0.113.9', text: 'x' }],
      scope: 'Host firewall of server web-01 only', scopeKind: 'SERVER', targetName: 'web-01', mode: 'DRY_RUN', backend: 'NFTABLES',
      command: 'nft add rule inet netmon input ip saddr 203.0.113.0/24 tcp dport 443 counter drop comment "netmon:<tag>"',
    });
    firewallApi.request.mockResolvedValue({ id: 'r9' });
    renderWithAuth(<RuleRequestPage />, { user: makeUser(['SECURITY_ANALYST']), route: '/ip-policies/new?integrationId=f1', path: '/ip-policies/new' });
    await screen.findByRole('option', { name: /web-01/ });
    expect(screen.getByText('Applies only to server web-01')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Submit for approval' })).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Remote address or network (CIDR)'), '203.0.113.0/24');
    await userEvent.type(screen.getByLabelText('local port'), '443');
    await userEvent.type(screen.getByLabelText('Reason'), 'Brute force against the web login');
    await userEvent.click(screen.getByRole('button', { name: 'Check and preview' }));
    expect(await screen.findByText(/tcp dport 443 counter drop/)).toBeInTheDocument();
    expect(screen.getByText('The rule covers the address you are connected from (203.0.113.9).')).toBeInTheDocument();
    const submit = screen.getByRole('button', { name: 'Submit for approval' });
    expect(submit).toBeDisabled();
    await userEvent.click(screen.getByLabelText('I have read these warnings'));
    await userEvent.click(submit);
    await waitFor(() => expect(firewallApi.request).toHaveBeenCalledWith(expect.objectContaining({
      integrationId: 'f1', action: 'BLOCK', direction: 'INBOUND', remote: '203.0.113.0/24', protocol: 'TCP', localPort: '443',
      acknowledgeWarnings: true,
    })));
    expect(firewallApi.request.mock.calls[0][0].expiresAt).toBeTruthy(); // 24 hours by default
  });

  it('shows refusals and blocks submission', async () => {
    firewallApi.preview.mockResolvedValue({ ok: false, errors: [{ code: 'PROTECTED', param: '10.0.0.5/32', text: 'x' }], warnings: [],
      scopeKind: 'SERVER', targetName: 'web-01', mode: 'DRY_RUN', command: 'nft ...' });
    renderWithAuth(<RuleRequestPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/ip-policies/new?integrationId=f1', path: '/ip-policies/new' });
    await screen.findByRole('option', { name: /web-01/ });
    await userEvent.type(screen.getByLabelText('Remote address or network (CIDR)'), '10.0.0.0/24');
    await userEvent.type(screen.getByLabelText('Reason'), 'Testing a protected range');
    await userEvent.click(screen.getByRole('button', { name: 'Check and preview' }));
    expect(await screen.findByText('The rule would block a protected management or monitoring address (10.0.0.5/32).')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Submit for approval' })).toBeDisabled();
  });

  it('lets another administrator approve, but not the requester', async () => {
    firewallApi.rule.mockResolvedValue({ rule: RULE, approvals: [] });
    firewallApi.approve.mockResolvedValue({});
    const { unmount } = renderWithAuth(<RuleDetailPage />, { user: makeUser(['NETWORK_ADMIN'], { username: 'ana' }), route: '/ip-policies/r1', path: '/ip-policies/:id' });
    expect(await screen.findByText('This is your own request. Another administrator must decide it.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument();
    unmount();
    renderWithAuth(<RuleDetailPage />, { user: makeUser(['NETWORK_ADMIN'], { username: 'neta' }), route: '/ip-policies/r1', path: '/ip-policies/:id' });
    await userEvent.click(await screen.findByRole('button', { name: 'Approve' }));
    expect(screen.getAllByText('Applies only to server web-01').length).toBeGreaterThan(0);
    await userEvent.click(screen.getAllByRole('button', { name: 'Approve' }).at(-1));
    await waitFor(() => expect(firewallApi.approve).toHaveBeenCalledWith('r1', null));
    expect(screen.getByRole('link', { name: /events of this rule/i })).toHaveAttribute('href', '/timeline?correlationId=r1');
  });

  it('is right-to-left in Dari with technical values left-to-right', async () => {
    firewallApi.rule.mockResolvedValue({ rule: { ...RULE, status: 'APPLIED', modeAtApply: 'ENFORCE' }, approvals: [] });
    renderWithAuth(<RuleDetailPage />, { user: makeUser(['READ_ONLY']), route: '/ip-policies/r1', path: '/ip-policies/:id', lang: 'fa' });
    expect(await screen.findAllByText('اعمال شده')).not.toHaveLength(0);
    expect(screen.getAllByText('203.0.113.0/24')[0]).toHaveAttribute('dir', 'ltr');
    expect(screen.queryByRole('button', { name: 'حذف قاعده' })).not.toBeInTheDocument();
  });
});
