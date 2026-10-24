package com.womensafety.controller;

import java.util.HashMap;
import java.util.Map;

import com.womensafety.config.NotificationProperties;
import com.womensafety.notification.TwilioSignature;
import com.womensafety.service.DeliveryReceiptService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Delivery receipts from the SMS provider. This is the only {@code /api} endpoint without a Firebase token, so it trusts
 * nothing: the request must carry a valid {@code X-Twilio-Signature} computed with our auth token over the callback URL
 * we configured (not one derived from request headers) and the posted parameters. Anything else is refused with 403
 * before any data is read. The endpoint does not exist unless real mode with Twilio credentials is configured.
 */
@RestController
@RequestMapping("/api/webhooks/sms")
public class SmsWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SmsWebhookController.class);
    private static final String ID_PATTERN = "^[A-Za-z0-9-]{1,64}$";

    private final NotificationProperties properties;
    private final DeliveryReceiptService receipts;

    public SmsWebhookController(NotificationProperties properties, DeliveryReceiptService receipts) {
        this.properties = properties;
        this.receipts = receipts;
    }

    @PostMapping(value = "/twilio", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> twilio(
            HttpServletRequest request,
            @RequestParam String alertId,
            @RequestParam String deliveryId) {
        if (!properties.realMode() || !properties.twilio().configured()) {
            return ResponseEntity.notFound().build();
        }
        if (!alertId.matches(ID_PATTERN) || !deliveryId.matches(ID_PATTERN)) {
            return ResponseEntity.status(403).build();
        }
        String url = properties.callbackUrl(alertId, deliveryId);
        Map<String, String> posted = new HashMap<>();
        request.getParameterMap().forEach((name, values) -> {
            if (!name.equals("alertId") && !name.equals("deliveryId") && values.length > 0) {
                posted.put(name, values[0]);
            }
        });
        if (url == null || !TwilioSignature.isValid(properties.twilio().authToken(), url, posted, request.getHeader("X-Twilio-Signature"))) {
            log.warn("Rejected an SMS callback with an invalid signature");
            return ResponseEntity.status(403).build();
        }
        receipts.applyTwilioReceipt(alertId, deliveryId, posted.get("MessageSid"), posted.get("MessageStatus"), posted.get("ErrorCode"));
        return ResponseEntity.noContent().build();
    }
}
