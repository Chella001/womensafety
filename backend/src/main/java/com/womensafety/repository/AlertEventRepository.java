package com.womensafety.repository;

import java.util.List;

import com.womensafety.model.AlertEvent;

/** Append-only history of an alert ({@code emergencyAlerts/{alertId}/events}). There is no update or delete. */
public interface AlertEventRepository {

    void append(String alertId, AlertEvent event);

    /** Oldest first. */
    List<AlertEvent> findAll(String alertId);
}
