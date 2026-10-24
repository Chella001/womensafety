package com.womensafety.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

import com.womensafety.model.AlertEvent;
import com.womensafety.model.Delivery;
import com.womensafety.model.DeliveryStatus;
import com.womensafety.repository.DeliveryRepository;
import org.springframework.stereotype.Service;

/**
 * Applies a provider's delivery receipt to the matching delivery. Callers must already have authenticated the request
 * (see {@code SmsWebhookController}). The only thing that can turn a delivery into DELIVERED is a receipt that names the
 * provider message id we stored when the provider accepted the message.
 */
@Service
public class DeliveryReceiptService {

    private final DeliveryRepository deliveries;
    private final NotificationSummaryService summaries;
    private final AlertEventLog events;
    private final Clock clock;

    public DeliveryReceiptService(DeliveryRepository deliveries, NotificationSummaryService summaries,
                                  AlertEventLog events, Clock clock) {
        this.deliveries = deliveries;
        this.summaries = summaries;
        this.events = events;
        this.clock = clock;
    }

    /** @return true when the receipt changed a delivery */
    public boolean applyTwilioReceipt(String alertId, String deliveryId, String messageSid, String messageStatus, String errorCode) {
        Delivery delivery = deliveries.findById(alertId, deliveryId).orElse(null);
        if (delivery == null || messageSid == null || !messageSid.equals(delivery.providerReference())) {
            return false; // unknown delivery, or a receipt for some other message: ignore
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String status = messageStatus == null ? "" : messageStatus.toLowerCase(Locale.ROOT);
        Delivery updated;
        switch (status) {
            case "delivered", "read" -> {
                if (delivery.status() == DeliveryStatus.DELIVERED) {
                    return false;
                }
                updated = delivery.receiptDelivered(now, now);
            }
            case "undelivered", "failed" -> {
                if (delivery.status() == DeliveryStatus.DELIVERED || delivery.status() == DeliveryStatus.FAILED) {
                    return false; // never walk a confirmed delivery backwards
                }
                updated = delivery.receiptFailed(errorCode == null || errorCode.isBlank() ? "UNDELIVERED" : "PROVIDER_" + errorCode, now);
            }
            default -> {
                return false; // queued / sending / sent: nothing new
            }
        }
        deliveries.save(alertId, updated);
        events.record(alertId, AlertEvent.DELIVERY_RECEIPT, "provider",
                Map.of("channel", delivery.channel().name(), "status", updated.status().name()));
        summaries.refresh(alertId, "provider");
        return true;
    }
}
