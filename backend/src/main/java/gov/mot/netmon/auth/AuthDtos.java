package gov.mot.netmon.auth;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import gov.mot.netmon.user.UserDtos;
import gov.mot.netmon.user.UserDtos.UserResponse;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 50) String username, @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "LoginRequest[username=" + username + "]";
        }
    }

    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt, UserResponse user) {
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(max = 128) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    public record SetupStatusResponse(boolean setupRequired) {
    }

    public record SetupAdminRequest(
            @NotBlank @Size(max = 200) String setupToken,
            @NotBlank @Pattern(regexp = UserDtos.USERNAME_REGEX,
                    message = "3-50 characters: letters, digits, dot, underscore or hyphen") String username,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Size(max = 128) String password) {
        @Override
        public String toString() {
            return "SetupAdminRequest[username=" + username + "]";
        }
    }
}
