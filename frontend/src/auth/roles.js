// Role codes (must match the backend RoleCode enum).
export const ROLES = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  INFRASTRUCTURE_ADMIN: 'INFRASTRUCTURE_ADMIN',
  NETWORK_ADMIN: 'NETWORK_ADMIN',
  SECURITY_ANALYST: 'SECURITY_ANALYST',
  READ_ONLY: 'READ_ONLY',
};

/**
 * UI permissions. These only decide which buttons and links are shown;
 * the backend enforces the same rules on every request.
 */
export const PERMISSIONS = {
  manageServers: [ROLES.SUPER_ADMIN, ROLES.INFRASTRUCTURE_ADMIN],
  deleteServers: [ROLES.SUPER_ADMIN],
  manageUsers: [ROLES.SUPER_ADMIN],
  viewAudit: [ROLES.SUPER_ADMIN, ROLES.SECURITY_ANALYST],
  // Packet captures: analysts may look at packet summaries and protocol trees; only network and
  // super administrators may start/stop captures, see raw bytes or download files.
  viewCaptures: [ROLES.SUPER_ADMIN, ROLES.NETWORK_ADMIN, ROLES.SECURITY_ANALYST],
  manageCaptures: [ROLES.SUPER_ADMIN, ROLES.NETWORK_ADMIN],
  deleteCaptureFiles: [ROLES.SUPER_ADMIN],
  // Alerts: everyone sees them; operators acknowledge/resolve; administrators change the rules.
  operateAlerts: [ROLES.SUPER_ADMIN, ROLES.INFRASTRUCTURE_ADMIN, ROLES.NETWORK_ADMIN, ROLES.SECURITY_ANALYST],
  manageAlertRules: [ROLES.SUPER_ADMIN, ROLES.INFRASTRUCTURE_ADMIN],
  viewSecurityEvents: [ROLES.SUPER_ADMIN, ROLES.SECURITY_ANALYST],
  // IP inventory: everyone reads; network and super administrators change. Flow data like captures.
  manageNetwork: [ROLES.SUPER_ADMIN, ROLES.NETWORK_ADMIN],
  viewTraffic: [ROLES.SUPER_ADMIN, ROLES.NETWORK_ADMIN, ROLES.SECURITY_ANALYST],
  // Firewall: everyone sees targets and rules; analysts may request; network/super admins approve
  // (never their own request) and manage targets; only a super admin may switch on enforcement.
  requestFirewallRules: [ROLES.SUPER_ADMIN, ROLES.NETWORK_ADMIN, ROLES.SECURITY_ANALYST],
  manageFirewall: [ROLES.SUPER_ADMIN, ROLES.NETWORK_ADMIN],
  enforceFirewall: [ROLES.SUPER_ADMIN],
};

export function hasAnyRole(user, roles) {
  if (!user || !roles || roles.length === 0) return Boolean(user);
  return user.roles?.some((r) => roles.includes(r)) ?? false;
}
