package com.womensafety.repository;

import java.util.List;
import java.util.Optional;

import com.womensafety.model.Delivery;

/** Per-recipient delivery records of an alert ({@code emergencyAlerts/{alertId}/deliveries}). Written by the server only. */
public interface DeliveryRepository {

    /** @return false (and changes nothing) when a delivery with this id already exists */
    boolean createIfAbsent(String alertId, Delivery delivery);

    Delivery save(String alertId, Delivery delivery);

    Optional<Delivery> findById(String alertId, String deliveryId);

    /** All deliveries of one alert (bounded: at most 5 contacts + the admin devices). */
    List<Delivery> findAll(String alertId);
}
