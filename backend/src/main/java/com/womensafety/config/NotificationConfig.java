package com.womensafety.config;

import com.womensafety.notification.MockSmsNotificationProvider;
import com.womensafety.notification.SmsNotificationProvider;
import com.womensafety.notification.TwilioSmsProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Chooses the SMS provider. TEST (the default) uses the mock. REAL requires complete Twilio settings and otherwise the
 * application REFUSES TO START, so it can never run "real" while silently doing nothing.
 */
@Configuration
@EnableScheduling
public class NotificationConfig {

    @Bean
    public SmsNotificationProvider smsNotificationProvider(NotificationProperties properties) {
        if (!properties.realMode()) {
            return new MockSmsNotificationProvider();
        }
        if (!properties.twilio().configured()) {
            throw new IllegalStateException("NOTIFICATIONS_MODE=REAL needs TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and "
                    + "TWILIO_FROM (or TWILIO_MESSAGING_SERVICE_SID). See the README, or use NOTIFICATIONS_MODE=TEST.");
        }
        return new TwilioSmsProvider(properties.twilio());
    }
}
