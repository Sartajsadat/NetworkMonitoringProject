package gov.mot.netmon.network;

import gov.mot.netmon.security.AuthenticatedUser;
import gov.mot.netmon.user.RoleCode;

/** Traffic metadata (flows, packet conversations) is limited like packet captures. */
public final class NetworkAccess {

    public static final String MANAGE = "hasAnyRole('SUPER_ADMIN','NETWORK_ADMIN')";
    public static final String VIEW_TRAFFIC = "hasAnyRole('SUPER_ADMIN','NETWORK_ADMIN','SECURITY_ANALYST')";

    private NetworkAccess() {
    }

    public static boolean canSeeTraffic(AuthenticatedUser u) {
        return u.hasRole(RoleCode.SUPER_ADMIN) || u.hasRole(RoleCode.NETWORK_ADMIN) || u.hasRole(RoleCode.SECURITY_ANALYST);
    }
}
