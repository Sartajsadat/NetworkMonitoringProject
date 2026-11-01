package gov.mot.netmon.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.ClientInfo;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.security.AuthenticatedUser;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.security.JwtService;
import gov.mot.netmon.security.RateLimiter;
import gov.mot.netmon.user.PasswordPolicy;
import gov.mot.netmon.user.User;
import gov.mot.netmon.user.UserDtos.UserResponse;
import gov.mot.netmon.user.UserRepository;

/**
 * Login, refresh-token rotation, logout and password change.
 *
 * <p>Refresh tokens are opaque random values; only their SHA-256 hash is stored. Each refresh
 * rotates the token. Presenting an already-rotated token is treated as theft and revokes every
 * session of that user.
 */
@Service
public class AuthService {

    /** Same message for unknown user, wrong password, locked and disabled: avoids account enumeration. */
    static final String GENERIC_LOGIN_FAILURE =
            "Invalid username or password, or the account is locked or disabled.";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final JwtService jwtService;
    private final AuditService audit;
    private final RateLimiter loginRateLimiter;
    private final AppProperties.Security props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
            PasswordPolicy passwordPolicy, JwtService jwtService, AuditService audit, RateLimiter loginRateLimiter,
            AppProperties appProperties, PlatformTransactionManager txManager, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.jwtService = jwtService;
        this.audit = audit;
        this.loginRateLimiter = loginRateLimiter;
        this.props = appProperties.security();
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        // Used to spend the same BCrypt time when the username does not exist.
        this.dummyHash = passwordEncoder.encode(TokenHashing.newToken());
    }

    /** Result of a successful login/refresh: the access token for the body, the refresh token for the cookie. */
    public record Session(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt,
            UserResponse user) {
    }

    public Session login(String rawUsername, String password) {
        String ip = ClientInfo.currentIp();
        String username = rawUsername.trim().toLowerCase(Locale.ROOT);
        if (!loginRateLimiter.tryAcquire("login:" + ip)) {
            audit.event(AuditAction.AUTH_LOGIN_FAILED).actor(null, username).reason("rate limited").failure();
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many login attempts. Wait a minute and try again.");
        }
        Optional<Session> session = tx.execute(status -> attemptLogin(username, password));
        if (session == null || session.isEmpty()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", GENERIC_LOGIN_FAILURE);
        }
        return session.get();
    }

    /** Runs inside a transaction that commits even on failure, so lockout counters persist. */
    private Optional<Session> attemptLogin(String username, String password) {
        Instant now = clock.instant();
        User user = users.findByUsername(username).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            audit.event(AuditAction.AUTH_LOGIN_FAILED).actor(null, username).reason("unknown user").failure();
            return Optional.empty();
        }
        if (!user.isActive()) {
            audit.event(AuditAction.AUTH_LOGIN_FAILED).actor(user.getId(), username).reason("account disabled")
                    .failure();
            return Optional.empty();
        }
        if (user.isLocked(now)) {
            audit.event(AuditAction.AUTH_LOGIN_FAILED).actor(user.getId(), username).reason("account locked")
                    .failure();
            return Optional.empty();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.registerFailedLogin(props.maxFailedLogins(), now.plus(props.lockoutDuration()));
            audit.event(AuditAction.AUTH_LOGIN_FAILED).actor(user.getId(), username)
                    .reason(user.isLocked(now) ? "bad password; account now locked" : "bad password").failure();
            return Optional.empty();
        }
        user.registerSuccessfulLogin(now);
        Session session = issueSession(user);
        audit.event(AuditAction.AUTH_LOGIN).actor(user.getId(), username).success();
        return Optional.of(session);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Session refresh(String presentedToken) {
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByTokenHash(TokenHashing.sha256(presentedToken))
                .orElseThrow(AuthService::sessionExpired);
        if (token.getRevokedAt() != null) {
            // A rotated token was replayed: assume it was stolen and kill every session for this user.
            refreshTokens.revokeAllForUser(token.getUserId(), now);
            users.findById(token.getUserId()).ifPresent(User::revokeAllTokens);
            audit.event(AuditAction.AUTH_TOKEN_REFRESH_REUSE_DETECTED).actor(token.getUserId(), null)
                    .target("USER", token.getUserId()).failure();
            throw sessionExpired();
        }
        if (!token.isActive(now)) {
            throw sessionExpired();
        }
        User user = users.findById(token.getUserId()).orElseThrow(AuthService::sessionExpired);
        if (!user.isActive() || user.isLocked(now)) {
            token.revoke(now, null);
            throw sessionExpired();
        }
        Session session = issueSession(user);
        RefreshToken replacement = refreshTokens.findByTokenHash(TokenHashing.sha256(session.refreshToken()))
                .orElseThrow();
        token.revoke(now, replacement.getId());
        return session;
    }

    @Transactional
    public void logout(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(TokenHashing.sha256(presentedToken)).ifPresent(t -> {
            if (t.getRevokedAt() == null) {
                t.revoke(clock.instant(), null);
                audit.event(AuditAction.AUTH_LOGOUT).actor(t.getUserId(), null).target("USER", t.getUserId())
                        .success();
            }
        });
    }

    @Transactional(readOnly = true)
    public UserResponse me() {
        AuthenticatedUser current = CurrentUser.require();
        return users.findById(current.id()).map(UserResponse::from)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    @Transactional
    public void changePassword(String currentPassword, String newPassword) {
        AuthenticatedUser current = CurrentUser.require();
        User user = users.findById(current.id()).orElseThrow(() -> ApiException.notFound("User"));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            audit.event(AuditAction.AUTH_PASSWORD_CHANGED).target("USER", user.getId())
                    .reason("current password incorrect").failure();
            throw ApiException.badRequest("WRONG_PASSWORD", "Current password is incorrect");
        }
        if (currentPassword.equals(newPassword)) {
            throw ApiException.badRequest("SAME_PASSWORD", "New password must differ from the current one");
        }
        passwordPolicy.validate(newPassword, user.getUsername());
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(user.getId(), clock.instant());
        audit.event(AuditAction.AUTH_PASSWORD_CHANGED).target("USER", user.getId()).success();
    }

    private Session issueSession(User user) {
        JwtService.IssuedToken access = jwtService.issueAccessToken(user);
        String raw = TokenHashing.newToken();
        Instant refreshExp = clock.instant().plus(props.refreshTokenTtl());
        refreshTokens.save(new RefreshToken(user.getId(), TokenHashing.sha256(raw), refreshExp,
                ClientInfo.currentIp(), ClientInfo.currentUserAgent()));
        return new Session(access.value(), access.expiresAt(), raw, refreshExp, UserResponse.from(user));
    }

    private static ApiException sessionExpired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Session expired. Please sign in again.");
    }
}
