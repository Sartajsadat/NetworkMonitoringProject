package gov.mot.netmon.auth;

import java.time.Clock;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.auth.AuthDtos.SetupAdminRequest;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.ClientInfo;
import gov.mot.netmon.config.AppProperties;
import gov.mot.netmon.security.RateLimiter;
import gov.mot.netmon.user.RoleCode;
import gov.mot.netmon.user.User;
import gov.mot.netmon.user.UserDtos.UserResponse;
import gov.mot.netmon.user.UserRepository;
import gov.mot.netmon.user.UserService;

/**
 * First-run administrator provisioning without default credentials.
 *
 * <p>While no user exists, the platform requires a one-time setup token to create the first
 * SUPER_ADMIN. The token is either supplied by the operator (BOOTSTRAP_SETUP_TOKEN) or generated
 * at startup and written only to the backend's own log, which only server operators can read.
 * Once an account exists, the setup endpoint is permanently closed.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);
    static final String SETUP_COMPLETED_KEY = "setup.completed_at";
    private static final int MIN_TOKEN_LENGTH = 24;

    private final UserRepository users;
    private final UserService userService;
    private final SystemSettingRepository settings;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final String configuredToken;
    private volatile String tokenHash;

    public SetupService(UserRepository users, UserService userService, SystemSettingRepository settings,
            AuditService audit, RateLimiter loginRateLimiter, Clock clock, AppProperties props) {
        this.users = users;
        this.userService = userService;
        this.settings = settings;
        this.audit = audit;
        this.rateLimiter = loginRateLimiter;
        this.clock = clock;
        this.configuredToken = props.bootstrap() == null ? null : props.bootstrap().setupToken();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prepareSetupToken() {
        if (users.count() > 0) {
            tokenHash = null;
            return;
        }
        String token;
        if (configuredToken != null && !configuredToken.isBlank()) {
            if (configuredToken.trim().length() < MIN_TOKEN_LENGTH) {
                throw new IllegalStateException("BOOTSTRAP_SETUP_TOKEN must be at least " + MIN_TOKEN_LENGTH
                        + " characters");
            }
            token = configuredToken.trim();
            log.warn("FIRST-RUN SETUP REQUIRED: no users exist. Use the BOOTSTRAP_SETUP_TOKEN value at /setup.");
        } else {
            token = TokenHashing.newToken();
            log.warn("""

                    ================================================================
                     FIRST-RUN SETUP REQUIRED - no user accounts exist.
                     Open the web UI at /setup and enter this one-time setup token:

                       {}

                     The token is valid until the first administrator is created
                     or the backend restarts. It is never shown through the API.
                    ================================================================""", token);
        }
        tokenHash = TokenHashing.sha256(token);
    }

    public boolean isSetupRequired() {
        return users.count() == 0;
    }

    @Transactional
    public synchronized UserResponse createFirstAdmin(SetupAdminRequest req) {
        if (!rateLimiter.tryAcquire("setup:" + ClientInfo.currentIp())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts, wait a minute");
        }
        if (!isSetupRequired()) {
            throw ApiException.conflict("SETUP_COMPLETED", "Setup has already been completed");
        }
        String expected = tokenHash;
        if (expected == null || !TokenHashing.constantTimeEquals(expected, TokenHashing.sha256(req.setupToken()))) {
            audit.event(AuditAction.SETUP_ATTEMPT_FAILED).actor(null, req.username()).reason("invalid setup token")
                    .failure();
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_SETUP_TOKEN", "Setup token is invalid");
        }
        User admin = userService.createUser(req.username(), req.email(), req.fullName(), req.password(),
                Set.of(RoleCode.SUPER_ADMIN));
        settings.save(new SystemSetting(SETUP_COMPLETED_KEY, clock.instant().toString()));
        tokenHash = null;
        audit.event(AuditAction.SETUP_ADMIN_CREATED).actor(admin.getId(), admin.getUsername())
                .target("USER", admin.getId()).success();
        log.info("First administrator '{}' created; setup endpoint is now closed.", admin.getUsername());
        return UserResponse.from(admin);
    }
}
