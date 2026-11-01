package gov.mot.netmon.user;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.mot.netmon.audit.AuditAction;
import gov.mot.netmon.audit.AuditService;
import gov.mot.netmon.auth.RefreshTokenRepository;
import gov.mot.netmon.common.ApiException;
import gov.mot.netmon.common.PageRequests;
import gov.mot.netmon.common.PageResponse;
import gov.mot.netmon.security.CurrentUser;
import gov.mot.netmon.user.UserDtos.CreateUserRequest;
import gov.mot.netmon.user.UserDtos.RoleResponse;
import gov.mot.netmon.user.UserDtos.UpdateUserRequest;
import gov.mot.netmon.user.UserDtos.UserResponse;

@Service
public class UserService {

    private static final Set<String> SORT_FIELDS = Set.of("username", "fullName", "email", "createdAt", "lastLoginAt");

    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;

    public UserService(UserRepository users, RoleRepository roles, RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String search, Boolean active, int page, int size, String sort) {
        Specification<User> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(root.get("username"), like), cb.like(root.get("email"), like),
                        cb.like(cb.lower(root.get("fullName")), like)));
            }
            if (active != null) {
                p.add(cb.equal(root.get("active"), active));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        var pageable = PageRequests.of(page, size, sort, SORT_FIELDS, Sort.by("username"));
        return PageResponse.of(users.findAll(spec, pageable), UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roles.findAll(Sort.by("id")).stream().map(RoleResponse::from).toList();
    }

    /** Creates a user. Used by the admin API and by first-run setup. */
    @Transactional
    public User createUser(String rawUsername, String rawEmail, String fullName, String password,
            Set<RoleCode> roleCodes) {
        String username = rawUsername.trim().toLowerCase(Locale.ROOT);
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);
        if (!username.matches(UserDtos.USERNAME_REGEX)) {
            throw ApiException.badRequest("INVALID_USERNAME",
                    "Username must be 3-50 characters: letters, digits, dot, underscore or hyphen");
        }
        passwordPolicy.validate(password, username);
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("USERNAME_TAKEN", "Username is already in use");
        }
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "Email is already in use");
        }
        User user = new User(username, email, fullName.trim(), passwordEncoder.encode(password),
                resolveRoles(roleCodes));
        return users.save(user);
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        User user = createUser(req.username(), req.email(), req.fullName(), req.password(), req.roles());
        audit.event(AuditAction.USER_CREATED).target("USER", user.getId())
                .detail("username", user.getUsername()).detail("roles", sorted(user.roleCodes())).success();
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest req) {
        User user = find(id);
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailAndIdNot(email, id)) {
            throw ApiException.conflict("EMAIL_TAKEN", "Email is already in use");
        }
        Set<RoleCode> before = user.roleCodes();
        Set<RoleCode> after = new HashSet<>(req.roles());
        if (before.contains(RoleCode.SUPER_ADMIN) && !after.contains(RoleCode.SUPER_ADMIN)) {
            guardSuperAdminRemoval(user, "remove the SUPER_ADMIN role from");
        }
        user.updateProfile(email, req.fullName().trim(), resolveRoles(after));
        audit.event(AuditAction.USER_UPDATED).target("USER", id).detail("username", user.getUsername())
                .detail("rolesBefore", sorted(before)).detail("rolesAfter", sorted(after)).success();
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse setActive(UUID id, boolean active) {
        User user = find(id);
        if (!active) {
            if (user.getId().equals(CurrentUser.require().id())) {
                throw ApiException.conflict("SELF_DEACTIVATION", "You cannot deactivate your own account");
            }
            if (user.isActive() && user.roleCodes().contains(RoleCode.SUPER_ADMIN)) {
                guardSuperAdminRemoval(user, "deactivate");
            }
            refreshTokens.revokeAllForUser(user.getId(), java.time.Instant.now());
        }
        user.setActive(active);
        audit.event(active ? AuditAction.USER_ACTIVATED : AuditAction.USER_DEACTIVATED).target("USER", id)
                .detail("username", user.getUsername()).success();
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse unlock(UUID id) {
        User user = find(id);
        user.unlock();
        audit.event(AuditAction.USER_UNLOCKED).target("USER", id).detail("username", user.getUsername()).success();
        return UserResponse.from(user);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        User user = find(id);
        passwordPolicy.validate(newPassword, user.getUsername());
        user.changePassword(passwordEncoder.encode(newPassword));
        user.unlock();
        refreshTokens.revokeAllForUser(user.getId(), java.time.Instant.now());
        audit.event(AuditAction.USER_PASSWORD_RESET).target("USER", id).detail("username", user.getUsername())
                .success();
    }

    private void guardSuperAdminRemoval(User user, String verb) {
        if (user.isActive() && users.countActiveWithRole(RoleCode.SUPER_ADMIN) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_SUPER_ADMIN",
                    "Cannot " + verb + " the last active SUPER_ADMIN");
        }
    }

    private Set<Role> resolveRoles(Set<RoleCode> codes) {
        List<Role> found = roles.findByCodeIn(codes);
        if (found.size() != codes.size()) {
            throw ApiException.badRequest("UNKNOWN_ROLE", "One or more roles are unknown");
        }
        return new HashSet<>(found);
    }

    private User find(UUID id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    private static List<String> sorted(Set<RoleCode> codes) {
        return codes.stream().map(Enum::name).sorted().toList();
    }
}
