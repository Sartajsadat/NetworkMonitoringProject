import { PERMISSIONS, ROLES } from '../auth/roles.js';

/**
 * Sidebar structure (labels are translation keys). Items with `phase` are planned modules that
 * are not built yet: shown disabled with their phase so nobody mistakes them for working features.
 * Items with `hidden: true` are left out of the sidebar for now (the pages still work by address);
 * delete the flag to show an item again. A section whose items are all hidden is not shown.
 */
export const NAV_SECTIONS = [
  { title: 'nav.section.overview', items: [{ to: '/', label: 'nav.dashboard', icon: 'speedometer2', end: true }] },
  {
    title: 'nav.section.infrastructure',
    items: [
      { to: '/servers', label: 'nav.servers', icon: 'hdd-stack' },
      { to: '/server-groups', label: 'nav.serverGroups', icon: 'collection', hidden: true },
      { to: '/monitoring/cpu-memory', label: 'nav.cpuMemory', icon: 'cpu' },
      { to: '/monitoring/disks', label: 'nav.disks', icon: 'device-hdd' },
      { to: '/monitoring/interfaces', label: 'nav.interfaces', icon: 'ethernet', hidden: true },
      { to: '/monitoring/services', label: 'nav.processes', icon: 'list-task' },
      { to: '/agents', label: 'nav.agents', icon: 'broadcast' },
      { label: 'nav.applications', icon: 'window-stack', phase: '5b', hidden: true },
    ],
  },
  {
    title: 'nav.section.network',
    items: [
      { to: '/analyzer', label: 'nav.packetAnalyzer', icon: 'diagram-3', roles: PERMISSIONS.viewCaptures, hidden: true },
      { to: '/captures', label: 'nav.captures', icon: 'record-circle', roles: PERMISSIONS.viewCaptures, hidden: true },
      { to: '/traffic', label: 'nav.traffic', icon: 'arrow-left-right', roles: PERMISSIONS.viewTraffic, hidden: true },
      { to: '/ip-addresses', label: 'nav.ipAddresses', icon: 'geo', hidden: true },
      { to: '/subnets', label: 'nav.subnets', icon: 'diagram-2', hidden: true },
      { to: '/network-devices', label: 'nav.networkDevices', icon: 'router', hidden: true },
    ],
  },
  {
    title: 'nav.section.accessControl',
    items: [
      { to: '/internet', label: 'nav.internet', icon: 'toggles', roles: PERMISSIONS.manageFirewall },
      { to: '/firewall', label: 'nav.firewall', icon: 'bricks', hidden: true },
      { to: '/ip-policies', label: 'nav.ipPolicies', icon: 'shield-lock', hidden: true },
      { to: '/app-access', label: 'nav.appAccess', icon: 'app-indicator', hidden: true },
    ],
  },
  {
    title: 'nav.section.investigation',
    items: [
      { to: '/timeline', label: 'nav.timeline', icon: 'clock-history', hidden: true },
      { to: '/alerts', label: 'nav.alerts', icon: 'bell' },
      { label: 'nav.logs', icon: 'journal-text', phase: '5b', hidden: true },
      { to: '/audit-logs', label: 'nav.audit', icon: 'shield-check', roles: PERMISSIONS.viewAudit },
    ],
  },
  {
    title: 'nav.section.administration',
    items: [
      { to: '/users', label: 'nav.users', icon: 'people', roles: [ROLES.SUPER_ADMIN] },
      { to: '/admin/health', label: 'nav.config', icon: 'heart-pulse', roles: PERMISSIONS.viewOperations },
      { to: '/admin/retention', label: 'nav.retention', icon: 'database-gear', roles: PERMISSIONS.viewOperations },
    ],
  },
];
