package gov.mot.netmon.firewall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * PERSONAL_MODE=true is for one person using NetMon on their own computers. Firewall rules and
 * application policies requested by an administrator are approved at once (no second person), and
 * a server whose agent allows enforcement is enforced without first switching its target to
 * Enforce. Off by default; the backend logs a warning and Platform health shows one while it is on.
 */
@Component
public class PersonalMode {

    private static final Logger log = LoggerFactory.getLogger(PersonalMode.class);

    private final boolean enabled;

    public PersonalMode(@Value("${PERSONAL_MODE:false}") boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            log.warn("PERSONAL_MODE=true: firewall changes are applied without a second person's approval. "
                    + "For personal use only - never on a shared or ministry deployment.");
        }
    }

    public boolean enabled() {
        return enabled;
    }
}
