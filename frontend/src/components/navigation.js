import { PERMISSIONS, ROLES } from '../auth/roles.js';

/**
 * Sidebar structure (labels are translation keys). Items with `phase` are planned modules that
 * are not built yet: shown disabled with their phase so nobody mistakes them for working features.
 */
export const NAV_SECTIONS = [
  { title: 'nav.section.overview', items: [{ to: '/', label: 'nav.dashboard', icon: 'speedometer2', end: true }] },
  {
    title: 'nav.section.infrastructure',
    items: [
      { to: '/servers', label: 'nav.servers', icon: 'hdd-stack' },
      { to: '/server-groups', label: 'nav.serverGroups', icon: 'collection' },
      { to: '/monitoring/cpu-memory', label: 'nav.cpuMemory', icon: 'cpu' },
      { to: '/monitoring/disks', label: 'nav.disks', icon: 'device-hdd' },
      { to: '/monitoring/interfaces', label: 'nav.interfaces', icon: 'ethernet' },
      { to: '/monitoring/services', label: 'nav.processes', icon: 'list-task' },
      { to: '/agents', label: 'nav.agents', icon: 'broadcast' },
      { label: 'nav.applications', icon: 'window-stack', phase: '5b' },
    ],
  },
  {
    title: 'nav.section.network',
    items: [
      { to: '/analyzer', label: 'nav.packetAnalyzer', icon: 'diagram-3', roles: PERMISSIONS.viewCaptures },
      { to: '/captures', label: 'nav.captures', icon: 'record-circle', roles: PERMISSIONS.viewCaptures },
      { to: '/traffic', label: 'nav.traffic', icon: 'arrow-left-right', roles: PERMISSIONS.viewTraffic },
      { to: '/ip-addresses', label: 'nav.ipAddresses', icon: 'geo' },
      { to: '/subnets', label: 'nav.subnets', icon: 'diagram-2' },
      { to: '/network-devices', label: 'nav.networkDevices', icon: 'router' },
    ],
  },
  {
    title: 'nav.section.accessControl',
    items: [
      { to: '/firewall', label: 'nav.firewall', icon: 'bricks' },
      { to: '/ip-policies', label: 'nav.ipPolicies', icon: 'shield-lock' },
      { label: 'nav.appAccess', icon: 'app-indicator', phase: 8 },
    ],
  },
  {
    title: 'nav.section.investigation',
    items: [
      { to: '/timeline', label: 'nav.timeline', icon: 'clock-history' },
      { to: '/alerts', label: 'nav.alerts', icon: 'bell' },
      { label: 'nav.logs', icon: 'journal-text', phase: '5b' },
      { to: '/audit-logs', label: 'nav.audit', icon: 'shield-check', roles: PERMISSIONS.viewAudit },
    ],
  },
  {
    title: 'nav.section.administration',
    items: [
      { to: '/users', label: 'nav.users', icon: 'people', roles: [ROLES.SUPER_ADMIN] },
      { label: 'nav.config', icon: 'sliders', phase: 9, roles: [ROLES.SUPER_ADMIN] },
      { label: 'nav.retention', icon: 'database-gear', phase: 9, roles: [ROLES.SUPER_ADMIN] },
    ],
  },
];
