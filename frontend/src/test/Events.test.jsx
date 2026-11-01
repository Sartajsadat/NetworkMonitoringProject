import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { makeUser, renderWithAuth } from './utils.jsx';
import { translate } from '../i18n/I18nContext.jsx';

vi.mock('../api/endpoints.js', () => ({
  eventApi: { search: vi.fn(), histogram: vi.fn(), get: vi.fn(), context: vi.fn() },
  alertApi: {
    list: vi.fn(), counts: vi.fn(), get: vi.fn(), acknowledge: vi.fn(), resolve: vi.fn(), rules: vi.fn(), ruleTypes: vi.fn(),
    createRule: vi.fn(), updateRule: vi.fn(), deleteRule: vi.fn(),
  },
  serverApi: { search: vi.fn() },
  metricsApi: { history: vi.fn() },
}));
const { eventApi, alertApi, serverApi, metricsApi } = await import('../api/endpoints.js');
const { default: TimelinePage } = await import('../pages/events/TimelinePage.jsx');
const { default: EventDetailPage } = await import('../pages/events/EventDetailPage.jsx');
const { default: AlertsPage } = await import('../pages/alerts/AlertsPage.jsx');
const { default: AlertRulesPage } = await import('../pages/alerts/AlertRulesPage.jsx');
const { eventSummary } = await import('../pages/events/eventText.js');

const EV_STOP = {
  id: 11, occurredAt: '2026-11-01T12:10:00Z', receivedAt: '2026-11-01T12:10:01Z', category: 'SERVICE', type: 'SERVICE_STATE_CHANGED',
  severity: 'ERROR', source: 'AGENT', serverId: 's1', serverName: 'mof-web-01', resource: 'nginx.service',
  summary: 'Service nginx.service changed from RUNNING to FAILED', details: { service: 'nginx.service', from: 'RUNNING', to: 'FAILED' },
};
const EV_CPU = {
  id: 12, occurredAt: '2026-11-01T12:20:00Z', receivedAt: '2026-11-01T12:20:00Z', category: 'PERFORMANCE', type: 'CPU_HIGH',
  severity: 'WARNING', source: 'PLATFORM', serverId: 's1', serverName: 'mof-web-01', summary: 'Alert', alertId: 'a1', alertStatus: 'OPEN',
  details: { rule: 'High CPU usage', ruleType: 'CPU_HIGH', value: 97.5, threshold: 90, unit: 'PERCENT', windowSeconds: 300 },
};
const ALERT = {
  id: 'a1', ruleId: 'r1', ruleName: 'High CPU usage', ruleType: 'CPU_HIGH', unit: 'PERCENT', category: 'PERFORMANCE', severity: 'WARNING',
  serverId: 's1', serverName: 'mof-web-01', status: 'OPEN', condition: 'CPU usage averaged 97.5%', threshold: 90, windowSeconds: 300,
  firstTriggeredAt: '2026-11-01T12:20:00Z', lastTriggeredAt: '2026-11-01T12:25:00Z', lastValue: 97.5, peakValue: 98, evaluations: 6,
};
const page = (content) => ({ content, page: 0, size: 50, totalElements: content.length, totalPages: 1 });
const fakeI18n = (lang) => {
  const t = (k, v) => translate(lang, k, v);
  return { t, formatNumber: String, formatDuration: (s) => `${s}s`, units: { percent: (v) => `${v}%`, bits: String, perSec: String } };
};

beforeEach(() => {
  vi.clearAllMocks();
  serverApi.search.mockResolvedValue(page([{ id: 's1', name: 'mof-web-01' }]));
  eventApi.histogram.mockResolvedValue({ buckets: [], bySeverity: { ERROR: 1, WARNING: 1 }, byCategory: { SERVICE: 1 }, total: 2, bucketSeconds: 900 });
  alertApi.counts.mockResolvedValue({ open: 1, acknowledged: 0, critical: 0, error: 0, warning: 1, info: 0 });
  metricsApi.history.mockResolvedValue({ series: [], expectedIntervalSeconds: 30 });
});

describe('event text', () => {
  it('describes events in the reader\'s language, translating states', () => {
    expect(eventSummary(EV_STOP, fakeI18n('en'))).toBe('nginx.service: Running → Failed');
    expect(eventSummary(EV_STOP, fakeI18n('fa'))).toBe('nginx.service: در حال اجرا ← ناکام');
    expect(eventSummary(EV_CPU, fakeI18n('en'))).toBe('"High CPU usage": 97.5% (threshold 90%)');
    expect(eventSummary({ ...EV_STOP, type: 'SOMETHING_NEW', details: {} }, fakeI18n('ps'))).toBe(EV_STOP.summary);
  });
});

describe('timeline', () => {
  it('applies filters from the link and shows events with their alert state', async () => {
    eventApi.search.mockResolvedValue(page([EV_CPU, EV_STOP]));
    renderWithAuth(<TimelinePage />, { user: makeUser(['READ_ONLY']), route: '/timeline?serverId=s1&minSeverity=WARNING' });
    expect(await screen.findByText('nginx.service: Running → Failed')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'High CPU usage' })).toHaveAttribute('href', '/events/12');
    expect(screen.getAllByText('Open').length).toBeGreaterThan(0);
    expect(eventApi.search).toHaveBeenCalledWith(expect.objectContaining({ serverId: 's1', minSeverity: 'WARNING' }));
    // read-only users get no security or capture category buttons
    expect(screen.queryByRole('button', { name: /security/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /packet capture/i })).not.toBeInTheDocument();
  });

  it('filters by category when a category is chosen', async () => {
    eventApi.search.mockResolvedValue(page([EV_STOP]));
    renderWithAuth(<TimelinePage />, { user: makeUser(['SECURITY_ANALYST']), route: '/timeline' });
    await userEvent.click(await screen.findByRole('button', { name: /^Security/ }));
    await waitFor(() => expect(eventApi.search).toHaveBeenLastCalledWith(expect.objectContaining({ category: ['SECURITY'] })));
  });
});

describe('event detail', () => {
  it('says plainly when no metrics exist for the period and lists what else happened', async () => {
    eventApi.context.mockResolvedValue({
      event: EV_STOP, related: [EV_CPU], windowFrom: '2026-11-01T11:40:00Z', windowTo: '2026-11-01T12:40:00Z',
      coverage: { samples: 0, expectedSamples: 120, percent: 0, expectedIntervalSeconds: 30, gaps: [{ from: '2026-11-01T11:40:00Z', to: '2026-11-01T12:40:00Z' }] },
      captures: [], capturesHidden: true,
    });
    renderWithAuth(<EventDetailPage />, { user: makeUser(['READ_ONLY']), route: '/events/11', path: '/events/:id' });
    expect(await screen.findByText(/No metrics were collected in this period/)).toBeInTheDocument();
    expect(screen.getByText(/You do not have access to packet captures/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'High CPU usage' })).toHaveAttribute('href', '/events/12');
    expect(metricsApi.history).not.toHaveBeenCalled(); // no empty chart pretending all was fine
  });
});

describe('alerts', () => {
  it('lets operators acknowledge with a comment; read-only users only look', async () => {
    alertApi.list.mockResolvedValue(page([ALERT]));
    alertApi.acknowledge.mockResolvedValue({ ...ALERT, status: 'ACKNOWLEDGED' });
    const { unmount } = renderWithAuth(<AlertsPage />, { user: makeUser(['READ_ONLY']), route: '/alerts' });
    expect(await screen.findByText(/CPU averaged 97\.5% over 5 min \(threshold 90%\)/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument();
    unmount();
    renderWithAuth(<AlertsPage />, { user: makeUser(['NETWORK_ADMIN']), route: '/alerts' });
    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }));
    const dialog = await screen.findByRole('dialog');
    await userEvent.type(within(dialog).getByLabelText(/comment/i), 'On it');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Acknowledge' }));
    await waitFor(() => expect(alertApi.acknowledge).toHaveBeenCalledWith('a1', 'On it'));
  });

  it('requires a note to resolve by hand', async () => {
    alertApi.list.mockResolvedValue(page([ALERT]));
    renderWithAuth(<AlertsPage />, { user: makeUser(['INFRASTRUCTURE_ADMIN']), route: '/alerts' });
    await userEvent.click(await screen.findByRole('button', { name: 'Resolve' }));
    const dialog = await screen.findByRole('dialog');
    const confirm = within(dialog).getByRole('button', { name: 'Resolve' });
    expect(confirm).toBeDisabled();
    await userEvent.type(within(dialog).getByLabelText(/what was done/i), 'Restarted batch');
    expect(confirm).toBeEnabled();
  });

  it('creates a throughput rule in Mb/s and sends bits per second', async () => {
    alertApi.rules.mockResolvedValue([]);
    alertApi.ruleTypes.mockResolvedValue([
      { type: 'CPU_HIGH', unit: 'PERCENT', needsThreshold: true, minThreshold: 1, maxThreshold: 100, usesWindow: true, serverScoped: true },
      { type: 'NETWORK_THROUGHPUT_HIGH', unit: 'BITS_PER_SECOND', needsThreshold: true, minThreshold: 1000, maxThreshold: 1e12, usesWindow: true, resourceKind: 'INTERFACE', serverScoped: true },
    ]);
    alertApi.createRule.mockResolvedValue({});
    renderWithAuth(<AlertRulesPage />, { user: makeUser(['INFRASTRUCTURE_ADMIN']), route: '/alerts/rules' });
    await userEvent.click(await screen.findByRole('button', { name: /new rule/i }));
    await userEvent.selectOptions(screen.getByLabelText('Condition'), 'NETWORK_THROUGHPUT_HIGH');
    await userEvent.type(screen.getByLabelText('Name'), 'Uplink busy');
    await userEvent.type(screen.getByLabelText('Threshold'), '800');
    await userEvent.type(screen.getByLabelText('Interface'), 'eth0');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(alertApi.createRule).toHaveBeenCalled());
    expect(alertApi.createRule.mock.calls[0][0]).toMatchObject({
      ruleType: 'NETWORK_THROUGHPUT_HIGH', threshold: 800e6, windowSeconds: 300, cooldownSeconds: 900, resourceFilter: 'eth0',
    });
  });

  it('shows rules read-only to users who may not change them', async () => {
    alertApi.rules.mockResolvedValue([{ id: 'r1', name: 'High CPU usage', ruleType: 'CPU_HIGH', unit: 'PERCENT', severity: 'WARNING',
      threshold: 90, windowSeconds: 300, cooldownSeconds: 900, enabled: true, activeAlerts: 1 }]);
    alertApi.ruleTypes.mockResolvedValue([]);
    renderWithAuth(<AlertRulesPage />, { user: makeUser(['READ_ONLY']), route: '/alerts/rules' });
    expect((await screen.findAllByText('High CPU usage')).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: /new rule/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument();
  });
});
