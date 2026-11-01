export const OS_LABELS = { LINUX: 'Linux', WINDOWS: 'Windows', OTHER: 'Other' };
export const ENV_LABELS = { DEVELOPMENT: 'Development', TESTING: 'Testing', STAGING: 'Staging', PRODUCTION: 'Production' };
export const GROUP_TYPE_LABELS = {
  DEPARTMENT: 'Department',
  ENVIRONMENT: 'Environment',
  APPLICATION: 'Application',
  SERVER_ROLE: 'Server role',
  CUSTOM: 'Custom',
};
export const MONITORING_STATUSES = ['PENDING_AGENT', 'ACTIVE', 'PAUSED', 'DECOMMISSIONED'];

export function toChartData(map, labels) {
  return Object.entries(map || {}).map(([k, v]) => ({ name: labels[k] || k, value: v }));
}
