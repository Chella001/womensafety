package com.womensafety.notification;

import java.util.Map;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Firebase Cloud Messaging through the Admin SDK. The payload carries a title, a generic body and the alert id only:
 * never a location, a phone number or any other personal data. Where no {@link FirebaseMessaging} bean exists (the
 * automated tests) every send reports UNAVAILABLE instead of pretending.
 */
@Component
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    private final ObjectProvider<FirebaseMessaging> messaging;

    public FcmPushSender(ObjectProvider<FirebaseMessaging> messaging) {
        this.messaging = messaging;
    }

    @Override
    public PushResult send(String token, String title, String body, Map<String, String> data) {
        FirebaseMessaging client = messaging.getIfAvailable();
        if (client == null) {
            return PushResult.of(PushResult.Outcome.UNAVAILABLE, "FCM_NOT_CONFIGURED");
        }
        Message message = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .putAllData(data)
                .build();
        try {
            return PushResult.accepted(client.send(message));
        } catch (FirebaseMessagingException e) {
            String code = e.getMessagingErrorCode() == null ? "FCM_ERROR" : "FCM_" + e.getMessagingErrorCode().name();
            log.warn("FCM rejected a push: {}", code);
            if (e.getMessagingErrorCode() == null) {
                return PushResult.of(PushResult.Outcome.TRANSIENT_FAILURE, code);
            }
            return switch (e.getMessagingErrorCode()) {
                case UNREGISTERED, INVALID_ARGUMENT, SENDER_ID_MISMATCH -> PushResult.of(PushResult.Outcome.INVALID_TOKEN, code);
                case QUOTA_EXCEEDED, UNAVAILABLE, INTERNAL -> PushResult.of(PushResult.Outcome.TRANSIENT_FAILURE, code);
                default -> PushResult.of(PushResult.Outcome.PERMANENT_FAILURE, code);
            };
        }
    }
}
