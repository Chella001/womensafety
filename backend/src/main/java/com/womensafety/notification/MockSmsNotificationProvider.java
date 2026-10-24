package com.womensafety.notification;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TEST MODE provider: sends nothing. It "accepts" every message with a reference that starts with {@code TEST-}, and the
 * worker stores such deliveries as SIMULATED, never as submitted or delivered. The message text is not logged.
 */
public class MockSmsNotificationProvider implements SmsNotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(MockSmsNotificationProvider.class);

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean simulated() {
        return true;
    }

    @Override
    public SmsSendResult send(SmsMessage message) {
        log.info("TEST MODE: simulated an SMS to {} (nothing was sent)", maskForLog(message.to()));
        return SmsSendResult.accepted("TEST-" + UUID.randomUUID());
    }

    static String maskForLog(String e164) {
        return e164 == null || e164.length() < 5 ? "***" : e164.substring(0, 3) + "***" + e164.substring(e164.length() - 2);
    }
}
