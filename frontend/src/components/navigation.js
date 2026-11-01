import { PERMISSIONS, ROLES } from '../auth/roles.js';

/**
 * Sidebar structure. Items with `phase` are planned modules that are not built yet:
 * they are shown disabled with their phase number so nobody mistakes them for working features.
 */
export const NAV_SECTIONS = [
  {
    title: 'Overview',
    items: [{ to: '/', label: 'Dashboard', icon: 'speedometer2', end: true }],
  },
  {
    title: 'Infrastructure',
    items: [
      { to: '/servers', label: 'Server inventory', icon: 'hdd-stack' },
      { to: '/server-groups', label: 'Server groups', icon: 'collection' },
      { label: 'CPU & memory', icon: 'cpu', phase: 2 },
      { label: 'Disks', icon: 'device-hdd', phase: 2 },
      { label: 'Network interfaces', icon: 'ethernet', phase: 2 },
      { label: 'Processes & services', icon: 'list-task', phase: 2 },
      { label: 'Agents', icon: 'broadcast', phase: 2 },
      { label: 'Hosted applications', icon: 'window-stack', phase: 5 },
    ],
  },
  {
    title: 'Network',
    items: [
      { label: 'Packet analyzer', icon: 'diagram-3', phase: 4 },
      { label: 'Captures', icon: 'record-circle', phase: 4 },
      { label: 'Traffic & conversations', icon: 'arrow-left-right', phase: 6 },
      { label: 'IP addresses', icon: 'geo', phase: 6 },
      { label: 'Subnets', icon: 'diagram-2', phase: 6 },
      { label: 'Network devices', icon: 'router', phase: 6 },
    ],
  },
  {
    title: 'Access control',
    items: [
      { label: 'Firewall integrations', icon: 'bricks', phase: 7 },
      { label: 'IP allow / block', icon: 'shield-lock', phase: 7 },
      { label: 'Application access', icon: 'app-indicator', phase: 8 },
    ],
  },
  {
    title: 'Investigation',
    items: [
      { label: 'Event timeline', icon: 'clock-history', phase: 5 },
      { label: 'Logs', icon: 'journal-text', phase: 5 },
      { label: 'Alerts', icon: 'bell', phase: 5 },
      { to: '/audit-logs', label: 'Audit log', icon: 'shield-check', roles: PERMISSIONS.viewAudit },
    ],
  },
  {
    title: 'Administration',
    items: [
      { to: '/users', label: 'Users & roles', icon: 'people', roles: [ROLES.SUPER_ADMIN] },
      { label: 'System configuration', icon: 'sliders', phase: 9, roles: [ROLES.SUPER_ADMIN] },
      { label: 'Retention & storage', icon: 'database-gear', phase: 9, roles: [ROLES.SUPER_ADMIN] },
    ],
  },
];
