package gov.mot.netmon.event;

import java.util.EnumSet;
import java.util.Set;

import gov.mot.netmon.security.AuthenticatedUser;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.user.RoleCode;

/**
 * Which event categories the current user may see. Security events (sign-in failures, source
 * addresses) are for security roles; capture events follow the packet-capture permissions.
 */
public final class EventAccess {

    private EventAccess() {
    }

    public static Set<EventCategory> hiddenCategories() {
        AuthenticatedUser u = CurrentUser.require();
        Set<EventCategory> hidden = EnumSet.noneOf(EventCategory.class);
        if (!canSeeSecurity(u)) hidden.add(EventCategory.SECURITY);
        if (!canSeeCaptures(u)) hidden.add(EventCategory.CAPTURE);
        return hidden;
    }

    public static boolean canSeeSecurity(AuthenticatedUser u) {
        return u.hasRole(RoleCode.SUPER_ADMIN) || u.hasRole(RoleCode.SECURITY_ANALYST);
    }

    public static boolean canSeeCaptures(AuthenticatedUser u) {
        return u.hasRole(RoleCode.SUPER_ADMIN) || u.hasRole(RoleCode.NETWORK_ADMIN) || u.hasRole(RoleCode.SECURITY_ANALYST);
    }
}
