package com.womensafety.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.womensafety.model.AlertEvent;
import com.womensafety.repository.AlertEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes the per-alert history. A failure to write a history entry is logged but never aborts the operation it
 * describes: an emergency alert must not be lost because a log line could not be stored.
 */
@Service
public class AlertEventLog {

    private static final Logger log = LoggerFactory.getLogger(AlertEventLog.class);

    private final AlertEventRepository repository;
    private final Clock clock;

    public AlertEventLog(AlertEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** @param actor "user", "system", or an administrator uid */
    public void record(String alertId, String type, String actor, Map<String, String> detail) {
        try {
            repository.append(alertId, new AlertEvent(UUID.randomUUID().toString(), type, actor,
                    clock.instant().truncatedTo(ChronoUnit.MICROS), detail == null ? Map.of() : detail));
        } catch (RuntimeException e) {
            log.warn("Could not store alert history entry {} ({})", type, e.getClass().getSimpleName());
        }
    }

    public List<AlertEvent> history(String alertId) {
        return repository.findAll(alertId);
    }
}
