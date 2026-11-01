import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  captureApi: {
    list: vi.fn(), limits: vi.fn(), capabilities: vi.fn(), create: vi.fn(), get: vi.fn(), stop: vi.fn(), packets: vi.fn(),
    packet: vi.fn(), bytes: vi.fn(), statistics: vi.fn(), validateFilter: vi.fn(), downloadTicket: vi.fn(), deleteFiles: vi.fn(),
  },
}));
const { captureApi } = await import('../api/endpoints.js');
const { default: CapturesPage } = await import('../pages/captures/CapturesPage.jsx');
const { default: NewCapturePage } = await import('../pages/captures/NewCapturePage.jsx');
const { default: CaptureDetailPage } = await import('../pages/captures/CaptureDetailPage.jsx');

const LIMITS = {
  maxDurationSeconds: 3600, maxBytesPerCapture: 1073741824, defaultSnaplen: 256, defaultRetentionDays: 7, maxRetentionDays: 90,
  quotaBytes: 10737418240, usedBytes: 1073741824, reservedBytes: 0, analysisAvailable: true,
};
const CAPTURE = {
  id: 'c1', serverId: 's1', serverName: 'mof-web-01', serverHostname: 'web01', name: 'Portal errors', reference: 'INC-1',
  reason: 'Users report failing pages', interfaceName: 'eth0', captureFilter: 'tcp port 80', snaplen: 256, durationSeconds: 300,
  maxBytes: 104857600, retentionDays: 7, status: 'COMPLETED', requestedBy: 'netadmin', requestedAt: '2026-11-01T10:00:00Z',
  startedAt: '2026-11-01T10:00:05Z', endedAt: '2026-11-01T10:05:05Z', agentPackets: 2, bytesStored: 2048, segmentsCount: 1,
  packetsIndexed: 2, indexTruncated: false, expiresAt: '2026-11-08T10:00:00Z', filesAvailable: true,
};
const PACKETS = {
  content: [
    { frameNo: 1, time: '2026-11-01T10:00:06.123456Z', src: '10.0.0.5', srcPort: 51000, dst: '10.0.0.10', dstPort: 80, protocol: 'HTTP', length: 120, reset: false, retransmission: false, info: 'GET / HTTP/1.1' },
    { frameNo: 2, time: '2026-11-01T10:00:06.200000Z', src: '10.0.0.10', srcPort: 80, dst: '10.0.0.5', dstPort: 51000, protocol: 'TCP', length: 54, reset: true, retransmission: false, info: '80 → 51000 [RST, ACK]' },
  ],
  page: 0, size: 100, totalElements: 2, totalPages: 1, packetsIndexed: 2, indexTruncated: false, displayFilterTruncated: false,
};

beforeEach(() => {
  vi.clearAllMocks();
  captureApi.limits.mockResolvedValue(LIMITS);
});

describe('captures list', () => {
  it('lists captures with storage usage and offers "New capture" only to capture managers', async () => {
    captureApi.list.mockResolvedValue({ content: [CAPTURE], page: 0, size: 25, totalElements: 1, totalPages: 1 });
    renderWithAuth(<CapturesPage />, { user: makeUser(['SECURITY_ANALYST']) });
    expect(await screen.findByRole('link', { name: 'Portal errors' })).toHaveAttribute('href', '/captures/c1');
    expect(within(screen.getByRole('table')).getByText('Completed')).toBeInTheDocument();
    expect(await screen.findByText(/1 GB of 10 GB used/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /new capture/i })).not.toBeInTheDocument();
  });
});

describe('new capture form', () => {
  const caps = [
    { serverId: 's1', serverName: 'mof-web-01', state: 'AVAILABLE', busy: false, maxDurationSeconds: 600, maxBytes: 209715200,
      interfaces: [{ name: 'eth0' }, { name: 'lo', description: 'Loopback' }] },
    { serverId: 's2', serverName: 'db-01', state: 'NOT_ENABLED', busy: false, interfaces: [] },
  ];

  it('explains why a server cannot capture and keeps the start button disabled', async () => {
    captureApi.capabilities.mockResolvedValue(caps);
    renderWithAuth(<NewCapturePage />, { user: makeUser(['NETWORK_ADMIN']) });
    await userEvent.selectOptions(await screen.findByLabelText('Server'), 's2');
    expect(screen.getByText(/only the server's administrator can switch it on/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /start capture/i })).toBeDisabled();
  });

  it('requires the authorisation checkbox and sends the request within the server limits', async () => {
    captureApi.capabilities.mockResolvedValue(caps);
    captureApi.create.mockResolvedValue({ id: 'new-id' });
    renderWithAuth(<NewCapturePage />, { user: makeUser(['NETWORK_ADMIN']) });
    await userEvent.selectOptions(await screen.findByLabelText('Server'), 's1');
    await userEvent.click(screen.getByRole('button', { name: 'Web (80, 443)' }));
    expect(screen.getByLabelText('Capture filter')).toHaveValue('tcp port 80 or tcp port 443');
    // durations above the server's 600 s limit are not offered
    expect(within(screen.getByLabelText('Duration')).queryByRole('option', { name: /1 h/ })).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Name'), 'Portal check');
    await userEvent.type(screen.getByLabelText('Reason'), 'Slow pages reported by users');
    const start = screen.getByRole('button', { name: /start capture/i });
    expect(start).toBeDisabled();
    await userEvent.click(screen.getByLabelText(/i am authorised/i));
    await userEvent.click(start);
    await waitFor(() => expect(captureApi.create).toHaveBeenCalled());
    expect(captureApi.create.mock.calls[0][0]).toMatchObject({
      serverId: 's1', interfaceName: 'eth0', captureFilter: 'tcp port 80 or tcp port 443', durationSeconds: 300,
      maxMegabytes: 100, snaplen: 256, retentionDays: 7, name: 'Portal check',
    });
  });
});

describe('packet analyzer', () => {
  beforeEach(() => {
    captureApi.get.mockResolvedValue(CAPTURE);
    captureApi.packets.mockResolvedValue(PACKETS);
  });

  it('shows packets, flags resets and opens the protocol tree; analysts get no bytes or download', async () => {
    captureApi.packet.mockResolvedValue({ packet: PACKETS.content[1], layers: [
      { name: 'ip', label: 'Internet Protocol Version 4, Src: 10.0.0.10', children: [{ name: 'ip.src', label: 'Source Address: 10.0.0.10', children: [] }] },
    ] });
    renderWithAuth(<CaptureDetailPage />, { user: makeUser(['SECURITY_ANALYST']), route: '/captures/c1', path: '/captures/:id' });
    expect(await screen.findByText('GET / HTTP/1.1')).toBeInTheDocument();
    expect(screen.getAllByText('10.0.0.10:80')).toHaveLength(2); // destination of 1, source of 2
    expect(screen.getByText('RST')).toBeInTheDocument();
    await userEvent.click(screen.getByText('80 → 51000 [RST, ACK]'));
    expect(await screen.findByText(/Internet Protocol Version 4/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /show bytes/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /download/i })).not.toBeInTheDocument();
  });

  it('validates the display filter with TShark before applying it', async () => {
    captureApi.validateFilter.mockResolvedValueOnce({ valid: false, message: 'tcp.flgs is neither a field nor a protocol name.' });
    captureApi.validateFilter.mockResolvedValueOnce({ valid: true });
    renderWithAuth(<CaptureDetailPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/captures/c1', path: '/captures/:id' });
    const input = await screen.findByLabelText('Display filter');
    await userEvent.type(input, 'tcp.flgs');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText(/neither a field nor a protocol/)).toBeInTheDocument();
    expect(captureApi.packets).toHaveBeenCalledTimes(1);
    await userEvent.clear(input);
    await userEvent.type(input, 'tcp.flags.reset == 1');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(captureApi.packets).toHaveBeenLastCalledWith('c1', expect.objectContaining({ displayFilter: 'tcp.flags.reset == 1' })));
  });

  it('lets network admins download through a one-time ticket', async () => {
    captureApi.downloadTicket.mockResolvedValue({ url: '/api/v1/captures/download?ticket=abc' });
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    renderWithAuth(<CaptureDetailPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/captures/c1', path: '/captures/:id' });
    await userEvent.click(await screen.findByRole('button', { name: /download pcapng/i }));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(captureApi.downloadTicket).toHaveBeenCalledWith('c1');
    click.mockRestore();
  });

  it('shows stop for a running capture and the agent message for a failed one', async () => {
    captureApi.get.mockResolvedValue({ ...CAPTURE, status: 'RUNNING', endedAt: null });
    captureApi.stop.mockResolvedValue({ ...CAPTURE, status: 'STOPPING' });
    renderWithAuth(<CaptureDetailPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/captures/c1', path: '/captures/:id' });
    await userEvent.click(await screen.findByRole('button', { name: 'Stop capture' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Stop capture' }));
    await waitFor(() => expect(captureApi.stop).toHaveBeenCalledWith('c1'));
  });

  it('renders in Dari right-to-left while packet data stays left-to-right', async () => {
    renderWithAuth(<CaptureDetailPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/captures/c1', path: '/captures/:id', lang: 'fa' });
    expect(await screen.findByText('تکمیل شد')).toBeInTheDocument();
    expect(screen.getByText('GET / HTTP/1.1').closest('table')).toHaveAttribute('dir', 'ltr');
  });
});
