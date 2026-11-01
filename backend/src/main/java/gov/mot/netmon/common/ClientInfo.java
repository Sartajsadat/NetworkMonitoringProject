package gov.mot.netmon.common;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Access to the current request's client address (already resolved through trusted proxies). */
public final class ClientInfo {

    private ClientInfo() {
    }

    public static String currentIp() {
        HttpServletRequest req = currentRequest();
        return req == null ? null : truncate(req.getRemoteAddr(), 45);
    }

    public static String currentUserAgent() {
        HttpServletRequest req = currentRequest();
        return req == null ? null : truncate(req.getHeader("User-Agent"), 255);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
