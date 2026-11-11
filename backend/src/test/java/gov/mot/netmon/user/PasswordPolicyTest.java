package gov.mot.netmon.user;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import gov.mot.netmon.common.ApiException;

class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    void acceptsStrongPassword() {
        assertThatCode(() -> policy.validate("Tr0ub4dor&3xyz", "bob")).doesNotThrowAnyException();
    }

    @Test
    void rejectsShortSimpleOrUsernameContainingPasswords() {
        assertThatThrownBy(() -> policy.validate("Short1!", "bob")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("onlylowercaseletters", "bob")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.validate("Admin-Password-123", "admin")).isInstanceOf(ApiException.class);
    }

    @Test
    void labSwitchAcceptsAnyNonEmptyPassword() {
        PasswordPolicy lab = new PasswordPolicy(true);
        assertThatCode(() -> lab.validate("123", "bob")).doesNotThrowAnyException();
        assertThatThrownBy(() -> lab.validate("", "bob")).isInstanceOf(ApiException.class);
    }
}
