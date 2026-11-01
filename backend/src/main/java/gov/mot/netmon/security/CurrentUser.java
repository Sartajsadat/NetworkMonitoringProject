package gov.mot.netmon.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import gov.mot.netmon.common.ApiException;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthenticatedUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication required");
    }

    public static String usernameOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedUser user ? user.username() : null;
    }
}
