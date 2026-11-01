package gov.mot.netmon.security;

import java.util.Set;
import java.util.UUID;

import gov.mot.netmon.user.RoleCode;

/** Principal placed in the SecurityContext after a valid access token has been checked against the DB. */
public record AuthenticatedUser(UUID id, String username, Set<RoleCode> roles) {

    public boolean hasRole(RoleCode role) {
        return roles.contains(role);
    }
}
