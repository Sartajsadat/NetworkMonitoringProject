package gov.mot.netmon.user;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request/response DTOs for user management. Entities are never serialized directly. */
public final class UserDtos {

    private UserDtos() {
    }

    public static final String USERNAME_REGEX = "^[A-Za-z0-9._-]{3,50}$";

    public record UserResponse(
            UUID id,
            String username,
            String email,
            String fullName,
            boolean active,
            boolean locked,
            Instant lockedUntil,
            Set<RoleCode> roles,
            Instant lastLoginAt,
            Instant passwordChangedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static UserResponse from(User u) {
            Instant now = Instant.now();
            return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.isActive(),
                    u.isLocked(now), u.isLocked(now) ? u.getLockedUntil() : null,
                    u.getRoles().stream().map(Role::getCode).collect(Collectors.toCollection(java.util.TreeSet::new)),
                    u.getLastLoginAt(), u.getPasswordChangedAt(), u.getCreatedAt(), u.getUpdatedAt());
        }
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = USERNAME_REGEX,
                    message = "3-50 characters: letters, digits, dot, underscore or hyphen") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(max = 128) String password,
            @NotEmpty Set<RoleCode> roles) {

        @Override
        public String toString() { // never log the password
            return "CreateUserRequest[username=" + username + ", roles=" + roles + "]";
        }
    }

    public record UpdateUserRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotEmpty Set<RoleCode> roles) {
    }

    public record ResetPasswordRequest(@NotBlank @Size(max = 128) String newPassword) {
        @Override
        public String toString() {
            return "ResetPasswordRequest[***]";
        }
    }

    public record RoleResponse(RoleCode code, String name, String description) {
        public static RoleResponse from(Role r) {
            return new RoleResponse(r.getCode(), r.getName(), r.getDescription());
        }
    }
}
