// Role codes (must match the backend RoleCode enum).
export const ROLES = {
  SUPER_ADMIN: 'SUPER_ADMIN',
  INFRASTRUCTURE_ADMIN: 'INFRASTRUCTURE_ADMIN',
  NETWORK_ADMIN: 'NETWORK_ADMIN',
  SECURITY_ANALYST: 'SECURITY_ANALYST',
  READ_ONLY: 'READ_ONLY',
};

export const ROLE_LABELS = {
  SUPER_ADMIN: 'Super admin',
  INFRASTRUCTURE_ADMIN: 'Infrastructure admin',
  NETWORK_ADMIN: 'Network admin',
  SECURITY_ANALYST: 'Security analyst',
  READ_ONLY: 'Read-only',
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
};

export function hasAnyRole(user, roles) {
  if (!user || !roles || roles.length === 0) return Boolean(user);
  return user.roles?.some((r) => roles.includes(r)) ?? false;
}
