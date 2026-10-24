package com.womensafety.notification;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.womensafety.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REAL MODE adapter for Twilio's Programmable Messaging REST API (no extra dependency: JDK HttpClient).
 *
 * <p>Twilio answering 201 with a message SID means the message was ACCEPTED for sending. It is stored as SUBMITTED;
 * only a later delivery receipt (see {@link TwilioSignature}) can make it DELIVERED.</p>
 *
 * <p>Using it for real needs a Twilio account, a sender that is allowed to message your recipients' countries (for
 * India: sender and template registration under TRAI DLT) and the recipients' consent. Credentials come from the
 * environment only. This adapter has not been exercised against Twilio's live service in this project.</p>
 */
public class TwilioSmsProvider implements SmsNotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsProvider.class);

    private final NotificationProperties.Twilio config;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public TwilioSmsProvider(NotificationProperties.Twilio config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String name() {
        return "twilio";
    }

    @Override
    public boolean simulated() {
        return false;
    }

    @Override
    public SmsSendResult send(SmsMessage message) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", message.to());
        form.put("Body", message.body());
        if (!config.messagingServiceSid().isBlank()) {
            form.put("MessagingServiceSid", config.messagingServiceSid());
        } else {
            form.put("From", config.from());
        }
        if (message.callbackUrl() != null) {
            form.put("StatusCallback", message.callbackUrl());
        }
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String credentials = Base64.getEncoder().encodeToString(
                (config.accountSid() + ":" + config.authToken()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        config.baseUrl() + "/2010-04-01/Accounts/" + config.accountSid() + "/Messages.json"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200 || status == 201) {
                JsonNode node = json.readTree(response.body());
                String sid = node.path("sid").asText("");
                return sid.isEmpty() ? SmsSendResult.transientFailure("NO_MESSAGE_ID") : SmsSendResult.accepted(sid);
            }
            String code = "HTTP_" + status;
            try {
                JsonNode node = json.readTree(response.body());
                if (node.hasNonNull("code")) {
                    code = "TWILIO_" + node.get("code").asText();
                }
            } catch (IOException ignored) {
                // keep the HTTP status code
            }
            // 429 and 5xx may succeed later; other 4xx (bad number, unverified sender, auth) will not.
            boolean retry = status == 429 || status >= 500;
            log.warn("Twilio rejected a message: {}", code);
            return retry ? SmsSendResult.transientFailure(code) : SmsSendResult.permanent(code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SmsSendResult.transientFailure("INTERRUPTED");
        } catch (IOException e) {
            log.warn("Twilio request failed: {}", e.getClass().getSimpleName());
            return SmsSendResult.transientFailure("PROVIDER_UNREACHABLE");
        }
    }
}
