import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  networkApi: {
    subnets: vi.fn(), createSubnet: vi.fn(), updateSubnet: vi.fn(), deleteSubnet: vi.fn(), ips: vi.fn(), ip: vi.fn(), createIp: vi.fn(),
    updateIp: vi.fn(), deleteIp: vi.fn(), importIps: vi.fn(), devices: vi.fn(), createDevice: vi.fn(), updateDevice: vi.fn(),
    deleteDevice: vi.fn(), flowSummary: vi.fn(), flows: vi.fn(), collector: vi.fn(),
  },
}));
const { networkApi } = await import('../api/endpoints.js');
const { default: SubnetsPage } = await import('../pages/network/SubnetsPage.jsx');
const { default: IpAddressesPage } = await import('../pages/network/IpAddressesPage.jsx');
const { default: IpDetailPage } = await import('../pages/network/IpDetailPage.jsx');
const { default: TrafficPage } = await import('../pages/network/TrafficPage.jsx');
const { default: DevicesPage } = await import('../pages/network/DevicesPage.jsx');

const page = (content) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });
const IP = {
  id: 'i1', address: '10.10.1.5', ipVersion: 4, subnetId: 's1', subnetCidr: '10.10.1.0/24', subnetName: 'Servers', serverId: 'srv',
  serverName: 'web-01', macAddress: '52:54:00:12:34:56', hostname: 'web-01.mot.local', allocation: 'ASSIGNED',
  sources: ['AGENT_INTERFACE', 'SERVER_REGISTRY'], firstSeenAt: '2026-11-01T10:00:00Z', lastSeenAt: new Date().toISOString(),
  lastSeenSource: 'AGENT_INTERFACE', seen: 'SEEN_NOW',
};
const SUMMARY = {
  from: '2026-11-02T10:00:00Z', to: '2026-11-02T11:00:00Z', flows: 3, bytes: 2050300, packets: 1542, sampled: true, bucketSeconds: 60,
  topSources: [{ address: '93.184.216.34', bytes: 2000000, packets: 1500, flows: 1 }], topDestinations: [], topPorts: [{ protocol: 6, port: 443, bytes: 2050000, packets: 1540, flows: 2 }],
  protocols: [{ protocol: 17, bytes: 300, packets: 2, flows: 1 }], conversations: [], timeline: [], exporters: [{ id: 'd1', name: 'edge', flows: 3, bytes: 2050300 }],
};

beforeEach(() => {
  vi.clearAllMocks();
  networkApi.subnets.mockResolvedValue([{ id: 's1', cidr: '10.10.1.0/24', ipVersion: 4, prefix: 24, name: 'Servers', usable: '254',
    knownAddresses: 12, seenLast24h: 9, usedPercent: 4.7 }]);
  networkApi.devices.mockResolvedValue([]);
  networkApi.collector.mockResolvedValue({ enabled: true, listening: true, netflowPort: 2055, ipfixPort: 4739, rejectedPackets: 0 });
});

describe('subnets', () => {
  it('creates a subnet with the VLAN as a number; read-only users cannot', async () => {
    networkApi.createSubnet.mockResolvedValue({});
    const { unmount } = renderWithAuth(<SubnetsPage />, { user: makeUser(['READ_ONLY']) });
    expect(await screen.findByRole('link', { name: '10.10.1.0/24' })).toHaveAttribute('href', '/ip-addresses?subnetId=s1');
    expect(screen.queryByRole('button', { name: /new subnet/i })).not.toBeInTheDocument();
    unmount();
    renderWithAuth(<SubnetsPage />, { user: makeUser(['NETWORK_ADMIN']) });
    await userEvent.click(await screen.findByRole('button', { name: /new subnet/i }));
    await userEvent.type(screen.getByLabelText('Network (CIDR)'), '10.20.0.0/16');
    await userEvent.type(screen.getByLabelText('Name'), 'Kabul DC2');
    await userEvent.type(screen.getByLabelText('VLAN'), '20');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(networkApi.createSubnet).toHaveBeenCalledWith(expect.objectContaining({ cidr: '10.20.0.0/16', vlanId: 20, gateway: null })));
  });
});

describe('IP addresses', () => {
  it('explains that unseen addresses are not proven free and filters from the link', async () => {
    networkApi.ips.mockResolvedValue(page([IP]));
    renderWithAuth(<IpAddressesPage />, { user: makeUser(['READ_ONLY']), route: '/ip-addresses?subnetId=s1&seen=SEEN_NOW' });
    expect(await screen.findByText('10.10.1.5')).toBeInTheDocument();
    expect(screen.getByText(/does not mean an address is free/)).toBeInTheDocument();
    expect(screen.getAllByText('Active now').length).toBeGreaterThan(0);
    expect(networkApi.ips).toHaveBeenCalledWith(expect.objectContaining({ subnetId: 's1', seen: 'SEEN_NOW' }));
    expect(screen.queryByRole('button', { name: /import csv/i })).not.toBeInTheDocument();
  });

  it('shows traffic only to roles allowed to see it', async () => {
    networkApi.ip.mockResolvedValue({ ip: IP, alerts: [], captures: [], capturesHidden: true, flowsHidden: true });
    const { unmount } = renderWithAuth(<IpDetailPage />, { user: makeUser(['READ_ONLY']), route: '/ip-addresses/i1', path: '/ip-addresses/:id' });
    expect((await screen.findAllByText(/available to network administrators and security analysts/)).length).toBe(2);
    expect(screen.queryByRole('link', { name: /traffic/i })).not.toBeInTheDocument();
    unmount();
    networkApi.ip.mockResolvedValue({ ip: IP, alerts: [], captures: [{ captureId: 'c1', captureName: 'Portal check', packets: 40, first: IP.firstSeenAt, last: IP.firstSeenAt }],
      capturesHidden: false, flowsHidden: false,
      flows: { flows: 2, bytesSent: 1000, bytesReceived: 2000000, topPeers: [{ address: '93.184.216.34', bytes: 2001000, flows: 2 }] } });
    renderWithAuth(<IpDetailPage />, { user: makeUser(['SECURITY_ANALYST']), route: '/ip-addresses/i1', path: '/ip-addresses/:id' });
    expect(await screen.findByText('93.184.216.34')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Portal check' })).toHaveAttribute('href', '/captures/c1');
  });
});

describe('traffic', () => {
  it('labels flow data as summaries, warns about sampling and drills down by address', async () => {
    networkApi.flowSummary.mockResolvedValue(SUMMARY);
    networkApi.flows.mockResolvedValue(page([]));
    renderWithAuth(<TrafficPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/traffic' });
    expect(await screen.findByText('Flow records, not packets.')).toBeInTheDocument();
    expect(screen.getByText(/Some exporters sample packets/)).toBeInTheDocument();
    expect(screen.getByText('TCP 443')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '93.184.216.34' }));
    await waitFor(() => expect(networkApi.flowSummary).toHaveBeenLastCalledWith(expect.objectContaining({ ip: '93.184.216.34' })));
  });
});

describe('devices', () => {
  it('says when the flow collector is off', async () => {
    networkApi.collector.mockResolvedValue({ enabled: false, listening: false, netflowPort: 2055, ipfixPort: 4739, rejectedPackets: 0 });
    networkApi.devices.mockResolvedValue([{ id: 'd1', name: 'core-fw', deviceType: 'FIREWALL', flowExport: true, exporterIp: '10.255.0.1',
      flowsReceived: 0, flowsDropped: 0 }]);
    renderWithAuth(<DevicesPage />, { user: makeUser(['NETWORK_ADMIN']) });
    expect(await screen.findByText(/The flow collector is off/)).toBeInTheDocument();
    const row = screen.getByText('core-fw').closest('tr');
    expect(within(row).getByText('No flows yet')).toBeInTheDocument();
  });
});
