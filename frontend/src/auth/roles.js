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
};

export function hasAnyRole(user, roles) {
  if (!user || !roles || roles.length === 0) return Boolean(user);
  return user.roles?.some((r) => roles.includes(r)) ?? false;
}
