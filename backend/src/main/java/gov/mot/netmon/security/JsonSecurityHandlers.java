package gov.mot.netmon.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.GlobalExceptionHandler;

/** Returns the standard JSON error body for 401/403 raised inside the security filter chain. */
@Component
public class JsonSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;
    private final AuditService audit;

    public JsonSecurityHandlers(ObjectMapper mapper, AuditService audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex)
            throws IOException {
        write(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required or session expired", req);
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) throws IOException {
        audit.recordAccessDenied(req.getMethod(), req.getRequestURI());
        write(res, HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action", req);
    }

    private void write(HttpServletResponse res, HttpStatus status, String code, String msg, HttpServletRequest req)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(),
                GlobalExceptionHandler.body(status, code, msg, req.getRequestURI(), List.of()));
    }
}
