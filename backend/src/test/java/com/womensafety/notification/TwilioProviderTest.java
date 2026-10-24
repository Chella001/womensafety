package com.womensafety.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import com.womensafety.config.NotificationProperties;
import com.womensafety.notification.SmsNotificationProvider.SmsMessage;
import com.womensafety.notification.SmsNotificationProvider.SmsSendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Twilio adapter against a local stand-in HTTP server (the JDK's built-in one). This checks how we form requests and
 * classify answers; it does NOT prove anything about the real Twilio service.
 */
class TwilioProviderTest {

    HttpServer server;
    final AtomicReference<String> authorization = new AtomicReference<>();
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> body = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    TwilioSmsProvider provider(int status, String json) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        return new TwilioSmsProvider(new NotificationProperties.Twilio("ACtest", "secret-token", "+15005550006", "",
                "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    static Map<String, String> form(String raw) {
        Map<String, String> m = new TreeMap<>();
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void acceptedMessage_returnsTheMessageSid_andSendsAuthenticatedFormRequest() throws Exception {
        var p = provider(201, "{\"sid\":\"SM0123\",\"status\":\"queued\"}");

        SmsSendResult r = p.send(new SmsMessage("+919876500001", "hello & welcome", "https://api.example.com/cb?alertId=a&deliveryId=b"));

        assertThat(r.outcome()).isEqualTo(SmsSendResult.Outcome.ACCEPTED);
        assertThat(r.providerReference()).isEqualTo("SM0123");
        assertThat(path.get()).isEqualTo("/2010-04-01/Accounts/ACtest/Messages.json");
        assertThat(authorization.get()).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("ACtest:secret-token".getBytes(StandardCharsets.UTF_8)));
        assertThat(form(body.get())).containsEntry("To", "+919876500001").containsEntry("Body", "hello & welcome")
                .containsEntry("From", "+15005550006").containsEntry("StatusCallback", "https://api.example.com/cb?alertId=a&deliveryId=b");
        assertThat(p.simulated()).isFalse();
    }

    @Test
    void aMessagingServiceTakesPrecedenceOverTheFromNumber() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"sid\":\"SM1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        var p = new TwilioSmsProvider(new NotificationProperties.Twilio("ACtest", "t", "+1500", "MG123", "http://127.0.0.1:" + server.getAddress().getPort()));
        p.send(new SmsMessage("+919876500001", "x", null));
        assertThat(form(body.get())).containsEntry("MessagingServiceSid", "MG123").doesNotContainKey("From").doesNotContainKey("StatusCallback");
    }

    @Test
    void clientErrors_arePermanent_withTheProviderCode() throws Exception {
        var p = provider(400, "{\"code\":21211,\"message\":\"Invalid 'To' Phone Number\"}");
        SmsSendResult r = p.send(new SmsMessage("+919876500001", "x", null));
        assertThat(r.outcome()).isEqualTo(SmsSendResult.Outcome.PERMANENT_FAILURE);
        assertThat(r.code()).isEqualTo("TWILIO_21211");
    }

    @Test
    void serverErrorsAndRateLimits_areTransient() throws Exception {
        assertThat(provider(503, "{}").send(new SmsMessage("+919876500001", "x", null)).outcome()).isEqualTo(SmsSendResult.Outcome.TRANSIENT_FAILURE);
        server.stop(0);
        var limited = provider(429, "{\"code\":20429}").send(new SmsMessage("+919876500001", "x", null));
        assertThat(limited.outcome()).isEqualTo(SmsSendResult.Outcome.TRANSIENT_FAILURE);
        assertThat(limited.code()).isEqualTo("TWILIO_20429");
    }

    @Test
    void anUnreachableProvider_isTransient_andNeverThrows() {
        var p = new TwilioSmsProvider(new NotificationProperties.Twilio("ACtest", "t", "+1500", "", "http://127.0.0.1:1"));
        SmsSendResult r = p.send(new SmsMessage("+919876500001", "x", null));
        assertThat(r.outcome()).isEqualTo(SmsSendResult.Outcome.TRANSIENT_FAILURE);
        assertThat(r.code()).isEqualTo("PROVIDER_UNREACHABLE");
    }

    @Test
    void mockProvider_isSimulated_andReturnsTestReferences() {
        var mock = new MockSmsNotificationProvider();
        SmsSendResult r = mock.send(new SmsMessage("+919876500001", "x", null));
        assertThat(mock.simulated()).isTrue();
        assertThat(r.providerReference()).startsWith("TEST-");
    }

    // ------------------------------------------------------------------------------------- callback signatures

    @Test
    void signature_matchesTwiliosPublishedExample() {
        // Example from Twilio's webhook-security documentation.
        Map<String, String> params = new TreeMap<>(Map.of(
                "CallSid", "CA1234567890ABCDE", "Caller", "+14158675310", "Digits", "1234", "From", "+14158675310", "To", "+18005551212"));
        String sig = TwilioSignature.sign("12345", "https://mycompany.com/myapp.php?foo=1&bar=2", params);
        assertThat(sig).isEqualTo("GvWf1cFY/Q7PnoempGyD5oXAezc=");
    }

    @Test
    void signature_rejectsTamperedParams_wrongUrl_wrongToken_andEmptyInputs() {
        Map<String, String> params = Map.of("MessageSid", "SM1", "MessageStatus", "delivered");
        String url = "https://api.example.com/api/webhooks/sms/twilio?alertId=a&deliveryId=b";
        String good = TwilioSignature.sign("tok", url, params);

        assertThat(TwilioSignature.isValid("tok", url, params, good)).isTrue();
        assertThat(TwilioSignature.isValid("tok", url, Map.of("MessageSid", "SM1", "MessageStatus", "failed"), good)).isFalse();
        assertThat(TwilioSignature.isValid("tok", url.replace("deliveryId=b", "deliveryId=c"), params, good)).isFalse();
        assertThat(TwilioSignature.isValid("other", url, params, good)).isFalse();
        assertThat(TwilioSignature.isValid("tok", url, params, null)).isFalse();
        assertThat(TwilioSignature.isValid("tok", url, params, "")).isFalse();
        assertThat(TwilioSignature.isValid("", url, params, good)).isFalse();
    }
}
