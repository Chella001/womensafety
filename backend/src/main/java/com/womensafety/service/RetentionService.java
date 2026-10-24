package com.womensafety.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.womensafety.config.PolicyProperties;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.repository.EmergencyAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Enforces the alert retention period ({@code ALERT_RETENTION_DAYS}, stored on every alert as {@code retainUntil}).
 *
 * <p>Once {@code retainUntil} has passed, a RESOLVED or CANCELLED alert is deleted together with its location, delivery
 * records, history and notification job. An alert that is still NEW or ACKNOWLEDGED is never deleted silently: it is
 * kept and counted as "waiting" until an administrator closes it, and the next run purges it. Each run that removes or
 * keeps anything writes one audit entry (alert ids and counts only, no personal data). The audit log itself is not
 * purged here.</p>
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final int SCAN_LIMIT = 500;
    private static final int MAX_IDS_IN_AUDIT = 50;

    private final EmergencyAlertRepository alerts;
    private final PolicyProperties policy;
    private final AuditService audit;
    private final Clock clock;

    public RetentionService(EmergencyAlertRepository alerts, PolicyProperties policy, AuditService audit, Clock clock) {
        this.alerts = alerts;
        this.policy = policy;
        this.audit = audit;
        this.clock = clock;
    }

    /** Hourly; the first run waits a few minutes after start-up so it never competes with the start-up itself. */
    @Scheduled(initialDelayString = "${app.policy.retention-initial-delay-ms:300000}", fixedDelayString = "${app.policy.retention-interval-ms:3600000}")
    void scheduledPurge() {
        if (!policy.retentionPurgeEnabled()) {
            return;
        }
        try {
            purgeExpired();
        } catch (RuntimeException e) {
            log.error("Retention purge failed and will be retried at the next run: {}", e.toString());
        }
    }

    /** @return what this run did */
    public Result purgeExpired() {
        List<EmergencyAlert> expired = alerts.findExpired(clock.instant(), SCAN_LIMIT);
        int deleted = 0;
        int failed = 0;
        List<String> deletedIds = new java.util.ArrayList<>();
        List<String> waiting = new java.util.ArrayList<>();
        for (EmergencyAlert alert : expired) {
            if (alert.status() == AlertStatus.ACTIVE || alert.status() == AlertStatus.ACKNOWLEDGED) {
                waiting.add(alert.id());
                continue;
            }
            try {
                alerts.deleteWithHistory(alert.id());
                deleted++;
                if (deletedIds.size() < MAX_IDS_IN_AUDIT) {
                    deletedIds.add(alert.id());
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Could not purge alert {}: {}", alert.id(), e.getClass().getSimpleName());
            }
        }
        Result result = new Result(deleted, waiting.size(), failed);
        if (deleted > 0 || failed > 0 || !waiting.isEmpty()) {
            audit.recordSystemEvent("RETENTION_PURGE", null, Map.of(
                    "retentionDays", String.valueOf(policy.alertRetentionDays()),
                    "deleted", String.valueOf(deleted),
                    "failed", String.valueOf(failed),
                    "keptBecauseStillOpen", String.valueOf(waiting.size()),
                    "deletedAlertIds", String.join(",", deletedIds),
                    "openAlertIds", String.join(",", waiting.subList(0, Math.min(waiting.size(), MAX_IDS_IN_AUDIT)))),
                    failed == 0);
            log.info("Retention purge: {} deleted, {} still open and kept, {} failed", deleted, waiting.size(), failed);
        }
        return result;
    }

    /**
     * @param deleted              alerts removed with their history
     * @param keptBecauseStillOpen expired alerts kept because an administrator has not resolved them yet
     * @param failed               alerts that could not be removed this time (retried next run)
     */
    public record Result(int deleted, int keptBecauseStillOpen, int failed) {
    }
}
