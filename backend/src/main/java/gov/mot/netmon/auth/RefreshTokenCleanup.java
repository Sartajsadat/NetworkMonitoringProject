package gov.mot.netmon.auth;

import java.time.Clock;
import java.time.Duration;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Removes refresh-token rows that expired more than a day ago. */
@Component
public class RefreshTokenCleanup {

    private final RefreshTokenRepository repository;
    private final Clock clock;

    public RefreshTokenCleanup(RefreshTokenRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void purge() {
        repository.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
    }
}
