package gov.mot.netmon.user;

import org.springframework.stereotype.Component;

import gov.mot.netmon.common.ApiException;

/**
 * Minimum password policy: 12-128 characters, at least three of the four character classes
 * (lower, upper, digit, symbol), and must not contain the username.
 */
@Component
public class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    public void validate(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw ApiException.badRequest("WEAK_PASSWORD",
                    "Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        int classes = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) classes++;
        if (password.chars().anyMatch(Character::isUpperCase)) classes++;
        if (password.chars().anyMatch(Character::isDigit)) classes++;
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) classes++;
        if (classes < 3) {
            throw ApiException.badRequest("WEAK_PASSWORD",
                    "Password must contain at least three of: lower-case, upper-case, digit, symbol");
        }
        if (username != null && password.toLowerCase().contains(username.toLowerCase())) {
            throw ApiException.badRequest("WEAK_PASSWORD", "Password must not contain the username");
        }
    }
}
