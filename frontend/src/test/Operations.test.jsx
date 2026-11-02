import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({ opsApi: { retention: vi.fn(), updateRetention: vi.fn(), runRetention: vi.fn(), health: vi.fn() } }));
const { opsApi } = await import('../api/endpoints.js');
const { default: RetentionPage } = await import('../pages/admin/RetentionPage.jsx');
const { default: HealthPage } = await import('../pages/admin/HealthPage.jsx');

const POLICIES = [
  { dataType: 'EVENTS', retentionDays: 180, minDays: 7, maxDays: 1825, fromEnvironment: true, updatedBy: 'system', rows: 1200, bytes: 4096000,
    oldest: '2026-06-01T00:00:00Z', lastRunAt: new Date().toISOString(), lastDeleted: 12 },
  { dataType: 'AUDIT_LOGS', retentionDays: 1095, minDays: 365, maxDays: 3650, fromEnvironment: false, updatedBy: 'root', rows: 50, bytes: 81920,
    lastRunAt: new Date().toISOString(), lastDeleted: 0, lastError: 'permission denied' },
];

beforeEach(() => {
  vi.clearAllMocks();
  opsApi.retention.mockResolvedValue(POLICIES);
});

describe('retention', () => {
  it('shows policies, storage and last results; only super admins change them', async () => {
    const { unmount } = renderWithAuth(<RetentionPage />, { user: makeUser(['INFRASTRUCTURE_ADMIN']) });
    expect(await screen.findByText('Timeline events')).toBeInTheDocument();
    expect(screen.getByText('180 days')).toBeInTheDocument();
    expect(screen.getByText('deployment default')).toBeInTheDocument();
    expect(screen.getByText('set by root')).toBeInTheDocument();
    expect(screen.getByText('12 deleted')).toBeInTheDocument();
    expect(screen.getByText('permission denied')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /run clean-up now/i })).not.toBeInTheDocument();
    unmount();
    opsApi.updateRetention.mockResolvedValue({});
    renderWithAuth(<RetentionPage />, { user: makeUser(['SUPER_ADMIN']) });
    await userEvent.click((await screen.findAllByRole('button', { name: 'Edit' }))[0]);
    const days = screen.getByLabelText('Days to keep');
    await userEvent.clear(days);
    await userEvent.type(days, '30');
    expect(screen.getByText(/shorter period deletes older data/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Reason'), 'Lab disk is small');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(opsApi.updateRetention).toHaveBeenCalledWith('EVENTS', 30, 'Lab disk is small'));
  });

  it('runs the clean-up only after confirmation', async () => {
    opsApi.runRetention.mockResolvedValue([{ dataType: 'EVENTS', deleted: 7 }, { dataType: 'FLOWS', deleted: 3 }]);
    renderWithAuth(<RetentionPage />, { user: makeUser(['SUPER_ADMIN']) });
    await userEvent.click(await screen.findByRole('button', { name: /run clean-up now/i }));
    expect(opsApi.runRetention).not.toHaveBeenCalled();
    await userEvent.click(screen.getAllByRole('button', { name: /run clean-up now/i }).at(-1));
    expect(await screen.findByText('Clean-up finished: 10 records deleted.')).toBeInTheDocument();
  });
});

describe('platform health', () => {
  it('shows the measured checks and settings without secrets', async () => {
    opsApi.health.mockResolvedValue({
      time: new Date().toISOString(), overall: 'WARNING',
      checks: [{ key: 'DATABASE', status: 'OK', message: 'PostgreSQL 16.4 reachable' }, { key: 'DISK', status: 'WARNING', message: 'Capture disk 88.0% full' }],
      runtime: { version: '0.1.0', javaVersion: '21.0.4', uptimeSeconds: 3600, heapUsedBytes: 1e8, heapMaxBytes: 1e9, timeZone: 'UTC' },
      database: { version: '16.4', schemaVersion: '8', sizeBytes: 5e7, connections: 7, maxConnections: '100', clockSkewMillis: 2 },
      largestTables: [{ table: 'metric_samples', bytes: 4e7, rows: 900000 }],
      storage: { captureDirectory: '/var/lib/netmon/captures', diskUsedPercent: 88, diskFreeBytes: 2e9, captureUsedBytes: 1e6, captureQuotaBytes: 1e10 },
      agents: { active: 3, reporting: 2, versions: [{ version: '0.7.0', n: 3 }] },
      jobs: { retentionLastRun: '2026-11-02T09:17:00Z', pendingAgentTasks: 0, flowCollectorEnabled: false },
      configuration: { REFRESH_COOKIE_SECURE: true, ACCESS_TOKEN_TTL: 'PT15M' },
    });
    renderWithAuth(<HealthPage />, { user: makeUser(['INFRASTRUCTURE_ADMIN']) });
    expect(await screen.findByText('Capture disk 88.0% full')).toBeInTheDocument();
    expect(screen.getAllByText('Warning').length).toBeGreaterThan(0);
    expect(screen.getByText('2 / 3')).toBeInTheDocument();
    expect(screen.getByText('metric_samples')).toBeInTheDocument();
    expect(screen.getByText('ACCESS_TOKEN_TTL')).toBeInTheDocument();
  });
});
