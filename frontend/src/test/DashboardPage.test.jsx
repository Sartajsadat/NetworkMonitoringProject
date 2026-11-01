import { screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';

vi.mock('../api/endpoints.js', () => ({ dashboardApi: { summary: vi.fn() } }));
const { dashboardApi } = await import('../api/endpoints.js');
const { default: DashboardPage } = await import('../pages/DashboardPage.jsx');

const summary = {
  generatedAt: new Date().toISOString(),
  totalServers: 0,
  connection: { ONLINE: 0, OFFLINE: 0, NO_AGENT: 0 },
  byMonitoringStatus: {}, byOsFamily: {}, byEnvironment: {},
  serverGroups: 0, activeUsers: 1,
  modules: [{ module: 'System metrics (CPU, memory, disk, network)', availability: 'NOT_COLLECTED', message: 'No monitoring agent is installed yet.' }],
  recentSecurityEvents: null,
};

describe('DashboardPage', () => {
  it('reports metrics as not collected instead of showing fake numbers', async () => {
    dashboardApi.summary.mockResolvedValue(summary);
    renderWithAuth(<DashboardPage />, { user: makeUser(['READ_ONLY']) });
    expect(await screen.findByText('Not collected')).toBeInTheDocument();
    expect(screen.getByText(/no monitoring agent is installed yet/i)).toBeInTheDocument();
    expect(screen.getAllByText(/no servers registered yet/i).length).toBeGreaterThan(0);
    expect(screen.queryByText(/recent security events/i)).not.toBeInTheDocument();
  });

  it('shows security events when provided', async () => {
    dashboardApi.summary.mockResolvedValue({
      ...summary,
      recentSecurityEvents: [{ id: 1, occurredAt: new Date().toISOString(), action: 'AUTH_LOGIN_FAILED', actorUsername: 'mallory', result: 'FAILURE' }],
    });
    renderWithAuth(<DashboardPage />, { user: makeUser(['SECURITY_ANALYST']) });
    expect(await screen.findByText('AUTH_LOGIN_FAILED')).toBeInTheDocument();
    expect(screen.getByText('mallory')).toBeInTheDocument();
  });
});
