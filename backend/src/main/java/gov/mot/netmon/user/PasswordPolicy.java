package gov.mot.netmon.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import gov.mot.netmon.common.ApiException;

/**
 * Minimum password policy: 12-128 characters, at least three of the four character classes
 * (lower, upper, digit, symbol), and must not contain the username.
 *
 * <p>LAB_WEAK_PASSWORDS=true turns the checks off (any non-empty password is accepted). It is for a
 * local lab only and must never be set on a real deployment; the backend logs a warning at start.
 */
@Component
public class PasswordPolicy {

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicy.class);

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 128;

    private final boolean labWeakPasswords;

    public PasswordPolicy() {
        this(false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PasswordPolicy(@Value("${LAB_WEAK_PASSWORDS:false}") boolean labWeakPasswords) {
        this.labWeakPasswords = labWeakPasswords;
        if (labWeakPasswords) {
            log.warn("LAB_WEAK_PASSWORDS=true: the password policy is OFF. Lab use only - remove it before real use.");
        }
    }

    public boolean isLabWeakPasswords() {
        return labWeakPasswords;
    }

    public void validate(String password, String username) {
        if (labWeakPasswords) {
            if (password == null || password.isEmpty() || password.length() > MAX_LENGTH) {
                throw ApiException.badRequest("WEAK_PASSWORD", "Password must not be empty");
            }
            return;
        }
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
