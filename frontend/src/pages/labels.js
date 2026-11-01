export const OS_CODES = ['LINUX', 'WINDOWS', 'OTHER'];
export const ENV_CODES = ['DEVELOPMENT', 'TESTING', 'STAGING', 'PRODUCTION'];
export const GROUP_TYPES = ['DEPARTMENT', 'ENVIRONMENT', 'APPLICATION', 'SERVER_ROLE', 'CUSTOM'];
export const MONITORING_STATUSES = ['PENDING_AGENT', 'ACTIVE', 'PAUSED', 'DECOMMISSIONED'];

/** {CODE: count} -> [{name: translated label, value}] for charts. */
export function toChartData(map, prefix, t) {
  return Object.entries(map || {}).map(([k, v]) => ({ name: t(`${prefix}.${k}`), value: v }));
}
