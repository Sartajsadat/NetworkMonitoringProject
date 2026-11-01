package gov.mot.netmon.auth;

import java.time.Duration;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import gov.mot.netmon.auth.AuthDtos.ChangePasswordRequest;
import gov.mot.netmon.auth.AuthDtos.LoginRequest;
import gov.mot.netmon.auth.AuthDtos.TokenResponse;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.user.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Authentication endpoints. The refresh token travels only in an httpOnly, SameSite=Strict cookie
 * limited to /api/v1/auth; the access token is returned in the body and kept in browser memory.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    public static final String REFRESH_COOKIE = "netmon_refresh";
    /** Custom header required on cookie-authenticated calls; browsers cannot add it cross-site without CORS. */
    public static final String CSRF_HEADER = "X-Requested-With";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final boolean secureCookie;

    public AuthController(AuthService authService, AppProperties props) {
        this.authService = authService;
        this.secureCookie = props.security().refreshCookieSecure();
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with username and password")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletResponse res) {
        return respond(authService.login(req.username(), req.password()), res);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Exchange the refresh cookie for a new access token (rotates the cookie)")
    public TokenResponse refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest req, HttpServletResponse res) {
        requireCsrfHeader(req);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "NO_SESSION", "No active session");
        }
        try {
            return respond(authService.refresh(refreshToken), res);
        } catch (ApiException e) {
            clearCookie(res);
            throw e;
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Sign out (revokes the refresh token)")
    public void logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest req, HttpServletResponse res) {
        requireCsrfHeader(req);
        authService.logout(refreshToken);
        clearCookie(res);
    }

    @GetMapping("/me")
    @Operation(summary = "Current user profile and roles")
    public UserResponse me() {
        return authService.me();
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change own password (signs out every session)")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req, HttpServletResponse res) {
        authService.changePassword(req.currentPassword(), req.newPassword());
        clearCookie(res);
    }

    private TokenResponse respond(AuthService.Session s, HttpServletResponse res) {
        Duration maxAge = Duration.between(Instant.now(), s.refreshExpiresAt());
        res.addHeader(HttpHeaders.SET_COOKIE, cookie(s.refreshToken(), maxAge).toString());
        res.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return new TokenResponse(s.accessToken(), "Bearer", s.accessExpiresAt(), s.user());
    }

    private void clearCookie(HttpServletResponse res) {
        res.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(secureCookie)
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }

    private static void requireCsrfHeader(HttpServletRequest req) {
        if (req.getHeader(CSRF_HEADER) == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MISSING_HEADER", CSRF_HEADER + " header is required");
        }
    }
}
