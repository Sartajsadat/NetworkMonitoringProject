import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeFormatters } from '../utils/format.js';
import { toRows } from '../components/MetricChart.jsx';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({
  agentApi: { list: vi.fn(), forServer: vi.fn(), createToken: vi.fn(), revoke: vi.fn(), updateConfig: vi.fn() },
  metricsApi: { fleet: vi.fn(), latest: vi.fn(), history: vi.fn() },
}));
const { agentApi, metricsApi } = await import('../api/endpoints.js');
const { default: AgentTab } = await import('../pages/server/AgentTab.jsx');
const { default: AgentsPage } = await import('../pages/monitoring/AgentsPage.jsx');
const { default: CpuMemoryPage } = await import('../pages/monitoring/CpuMemoryPage.jsx');
const { default: OverviewTab } = await import('../pages/server/OverviewTab.jsx');

const notEnrolled = {
  serverId: 's1', serverName: 'web-01', serverHostname: 'web-01.mot.local', status: 'NOT_ENROLLED', connectionStatus: 'NO_AGENT',
  hostnameMismatch: false, lastErrorCount: 0, config: { reportIntervalSeconds: 30, services: [] },
};

describe('unit formatting', () => {
  it('formats bytes, bit rates and percentages', () => {
    const f = makeFormatters('en-GB');
    expect(f.bytes(1536)).toBe('1.5 KB');
    expect(f.bits(12_500_000)).toBe('12.5 Mb/s');
    expect(f.percent(45.67)).toBe('45.7%');
    expect(f.bytes(null)).toBe('—');
  });

  it('uses Afghan digits for Dari', () => {
    const f = makeFormatters('fa-AF-u-nu-arabext', true);
    expect(f.bytes(2048)).toBe('\u2066۲ KB\u2069'); // isolated so number and unit stay together in RTL text
    expect(f.bytes(null)).toBe('—');
  });
});

describe('chart gap handling', () => {
  it('inserts a null point where samples are missing so the line breaks', () => {
    const history = {
      expectedIntervalSeconds: 30,
      series: [{ metric: 'cpu.usage_pct', resource: '', points: [
        { t: '2026-11-01T10:00:00Z', v: 1 }, { t: '2026-11-01T10:00:30Z', v: 2 }, { t: '2026-11-01T10:10:00Z', v: 3 },
      ] }],
    };
    const rows = toRows(history, (s) => s.metric);
    expect(rows).toHaveLength(4);
    expect(rows[2]['cpu.usage_pct']).toBeNull();
    expect(rows[3]['cpu.usage_pct']).toBe(3);
  });
});

describe('agent enrollment', () => {
  beforeEach(() => {
    agentApi.forServer.mockReset().mockResolvedValue(notEnrolled);
    agentApi.createToken.mockReset().mockResolvedValue({
      token: 'tok-123', expiresAt: new Date(Date.now() + 86400000).toISOString(), serverId: 's1', serverName: 'web-01', platformUrl: 'https://netmon.example',
    });
  });

  it('creates a one-time token and shows the install command containing it', async () => {
    renderWithAuth(<AgentTab serverId="s1" latest={null} canManage />, { user: makeUser(['INFRASTRUCTURE_ADMIN']) });
    await userEvent.click(await screen.findByRole('button', { name: /create enrollment token/i }));
    expect(await screen.findByText('tok-123')).toBeInTheDocument();
    expect(document.getElementById('install-cmd').textContent).toContain('--url https://netmon.example');
    expect(document.getElementById('install-cmd').textContent).toContain('--token tok-123');
  });

  it('hides management actions from read-only users', async () => {
    renderWithAuth(<AgentTab serverId="s1" latest={null} canManage={false} />, { user: makeUser(['READ_ONLY']) });
    await screen.findByText(/no agent is enrolled/i);
    expect(screen.queryByRole('button', { name: /create enrollment token/i })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/report interval/i)).toBeDisabled();
  });

  it('rejects invalid systemd unit names before sending', async () => {
    agentApi.forServer.mockResolvedValue({ ...notEnrolled, status: 'ACTIVE', agentId: 'a1' });
    renderWithAuth(<AgentTab serverId="s1" latest={null} canManage />, { user: makeUser(['SUPER_ADMIN']) });
    const box = await screen.findByLabelText(/services to monitor/i);
    await userEvent.type(box, 'nginx.service{enter}bad;name');
    expect(screen.getByText(/not valid unit names: bad;name/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled();
  });
});

describe('Windows agents', () => {
  beforeEach(() => {
    agentApi.createToken.mockReset().mockResolvedValue({
      token: 'tok-win', expiresAt: new Date(Date.now() + 86400000).toISOString(), serverId: 's1', serverName: 'app-win-01', platformUrl: 'https://netmon.example',
    });
  });

  it('shows the PowerShell installer for Windows servers', async () => {
    agentApi.forServer.mockReset().mockResolvedValue({ ...notEnrolled, serverOsFamily: 'WINDOWS' });
    renderWithAuth(<AgentTab serverId="s1" latest={null} canManage />, { user: makeUser(['SUPER_ADMIN']) });
    await userEvent.click(await screen.findByRole('button', { name: /create enrollment token/i }));
    await screen.findByText('tok-win');
    const cmd = document.getElementById('install-cmd').textContent;
    expect(cmd).toContain('install-agent.ps1');
    expect(cmd).toContain('-WinSW');
    expect(cmd).toContain('-Token tok-win');
    expect(screen.getByText(/LOCAL SERVICE/)).toBeInTheDocument();
  });

  it('warns when the agent OS differs from the registered OS', async () => {
    agentApi.forServer.mockReset().mockResolvedValue({ ...notEnrolled, status: 'ACTIVE', agentId: 'a1', serverOsFamily: 'WINDOWS',
      platform: 'LINUX', platformMismatch: true });
    renderWithAuth(<AgentTab serverId="s1" latest={null} canManage />, { user: makeUser(['SUPER_ADMIN']) });
    expect(await screen.findByText(/the agent runs on linux, but this server is registered as windows/i)).toBeInTheDocument();
  });

  it('shows the processor queue instead of a load average', async () => {
    metricsApi.fleet.mockResolvedValue([
      { serverId: 'w', name: 'app-win-01', connectionStatus: 'ONLINE', collectedAt: new Date().toISOString(), stale: false,
        host: { platform: 'WINDOWS', cpuCount: 8 }, cpu: { usagePercent: 30, runQueue: 2 }, memory: { usedPercent: 50, usedBytes: 1, totalBytes: 2 } },
    ]);
    renderWithAuth(<CpuMemoryPage />);
    expect(await screen.findByText('Processor queue 2')).toBeInTheDocument();
  });
});

describe('fleet pages', () => {
  it('lists agent states including servers without an agent', async () => {
    agentApi.list.mockResolvedValue([
      notEnrolled,
      { ...notEnrolled, serverId: 's2', serverName: 'db-02', status: 'ACTIVE', agentId: 'a2', connectionStatus: 'ONLINE',
        agentVersion: '0.2.0', hostnameMismatch: true, reportedHostname: 'other-host', lastErrorCount: 2 },
    ]);
    renderWithAuth(<AgentsPage />);
    expect(await screen.findByText('db-02')).toBeInTheDocument();
    expect(screen.getAllByText('Not enrolled').length).toBeGreaterThan(0);
    expect(screen.getByText('other-host')).toBeInTheDocument();
  });

  it('shows an honest empty state when no agent has reported', async () => {
    metricsApi.fleet.mockResolvedValue([]);
    renderWithAuth(<CpuMemoryPage />);
    expect(await screen.findByText(/no agent has reported yet/i)).toBeInTheDocument();
  });

  it('shows live CPU and memory values, busiest first', async () => {
    metricsApi.fleet.mockResolvedValue([
      { serverId: 'a', name: 'quiet', connectionStatus: 'ONLINE', collectedAt: new Date().toISOString(), stale: false,
        cpu: { usagePercent: 5, load1: 0.1, load5: 0.1, load15: 0.1 }, memory: { usedPercent: 20, usedBytes: 1, totalBytes: 5 } },
      { serverId: 'b', name: 'busy', connectionStatus: 'ONLINE', collectedAt: new Date().toISOString(), stale: true,
        cpu: { usagePercent: 95, load1: 3, load5: 2, load15: 1 }, memory: { usedPercent: 80, usedBytes: 4, totalBytes: 5 } },
    ]);
    renderWithAuth(<CpuMemoryPage />);
    await waitFor(() => expect(screen.getByText('busy')).toBeInTheDocument());
    const links = screen.getAllByRole('link').map((l) => l.textContent);
    expect(links.indexOf('busy')).toBeLessThan(links.indexOf('quiet'));
    expect(screen.getByText('95%')).toBeInTheDocument();
    expect(screen.getByText('Stale')).toBeInTheDocument();
  });
});

describe('server overview', () => {
  const server = { name: 'web-01', osFamily: 'LINUX', environment: 'PRODUCTION', organization: 'MoH', monitoringStatus: 'PENDING_AGENT',
    ipAddresses: [], groups: [], createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };

  it('says plainly when no agent is installed instead of showing zeros', () => {
    renderWithAuth(<OverviewTab server={server} latest={{ available: false, reason: 'NO_AGENT' }} />);
    expect(screen.getByText(/no monitoring agent is installed/i)).toBeInTheDocument();
    expect(screen.queryByText('0%')).not.toBeInTheDocument();
  });

  it('shows live values in Pashto', () => {
    const latest = { available: true, stale: false, collectedAt: new Date().toISOString(),
      report: { cpu: { usagePercent: 42, load1: 1, load5: 1, load15: 1 }, memory: { usedPercent: 50, usedBytes: 1024, totalBytes: 2048 },
        disks: [{ mount: '/', usedPercent: 70 }], network: [], host: { uptimeSeconds: 90000 } } };
    renderWithAuth(<OverviewTab server={server} latest={latest} />, { lang: 'ps' });
    expect(screen.getByText('ژوندی')).toBeInTheDocument();
    expect(screen.getByText('\u2066۴۲٪\u2069')).toBeInTheDocument();
  });
});
