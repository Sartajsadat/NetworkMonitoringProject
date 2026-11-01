package gov.mot.netmon.user;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at", nullable = false)
    private Instant passwordChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    protected User() {
    }

    public User(String username, String email, String fullName, String passwordHash, Set<Role> roles) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.username = username;
        this.email = email;
        this.fullName = fullName;
        this.passwordHash = passwordHash;
        this.roles = new HashSet<>(roles);
        this.passwordChangedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Set<RoleCode> roleCodes() {
        return roles.stream().map(Role::getCode).collect(Collectors.toSet());
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Registers a failed login; locks the account once {@code maxFailures} is reached. */
    public void registerFailedLogin(int maxFailures, Instant lockUntil) {
        failedLoginCount++;
        if (failedLoginCount >= maxFailures) {
            lockedUntil = lockUntil;
            failedLoginCount = 0;
        }
        touch();
    }

    public void registerSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
        touch();
    }

    public void changePassword(String newHash) {
        passwordHash = newHash;
        passwordChangedAt = Instant.now();
        revokeAllTokens();
    }

    /** Invalidates every access token already issued to this user. */
    public void revokeAllTokens() {
        tokenVersion++;
        touch();
    }

    public void unlock() {
        lockedUntil = null;
        failedLoginCount = 0;
        touch();
    }

    public void setActive(boolean active) {
        if (this.active && !active) {
            revokeAllTokens();
        }
        this.active = active;
        touch();
    }

    public void updateProfile(String email, String fullName, Set<Role> roles) {
        this.email = email;
        this.fullName = fullName;
        this.roles.clear();
        this.roles.addAll(roles);
        touch();
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getFullName() { return fullName; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return active; }
    public int getFailedLoginCount() { return failedLoginCount; }
    public Instant getLockedUntil() { return lockedUntil; }
    public int getTokenVersion() { return tokenVersion; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public Instant getPasswordChangedAt() { return passwordChangedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Set<Role> getRoles() { return roles; }
}
