package com.womensafety.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import com.womensafety.config.RateLimitProperties;
import org.junit.jupiter.api.Test;

class RateLimitFilterTest {

    private static final class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_000_000L);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    private final MutableClock clock = new MutableClock();
    private final RateLimitFilter filter =
            new RateLimitFilter(new RateLimitProperties(true, 3, 10, 30, 10), mock(JsonSecurityErrorHandler.class), clock);

    @Test
    void allowsUpToTheLimit_thenReportsHowLongToWait() {
        assertThat(filter.tryAcquire("1.2.3.4|auth", 3)).isZero();
        assertThat(filter.tryAcquire("1.2.3.4|auth", 3)).isZero();
        assertThat(filter.tryAcquire("1.2.3.4|auth", 3)).isZero();
        assertThat(filter.tryAcquire("1.2.3.4|auth", 3)).isBetween(1L, 60L);
    }

    @Test
    void windowSlides_soRequestsAreAllowedAgainAfterAMinute() {
        for (int i = 0; i < 3; i++) {
            filter.tryAcquire("a|auth", 3);
        }
        assertThat(filter.tryAcquire("a|auth", 3)).isPositive();
        clock.millis.addAndGet(60_001);
        assertThat(filter.tryAcquire("a|auth", 3)).isZero();
    }

    @Test
    void clientsAreCountedSeparately() {
        for (int i = 0; i < 3; i++) {
            filter.tryAcquire("a|auth", 3);
        }
        assertThat(filter.tryAcquire("a|auth", 3)).isPositive();
        assertThat(filter.tryAcquire("b|auth", 3)).isZero();
    }
}
