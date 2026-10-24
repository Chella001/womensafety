package com.womensafety.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.womensafety.config.AdminProperties;
import com.womensafety.config.CorsProperties;
import com.google.firebase.messaging.FirebaseMessaging;
import com.womensafety.config.FirebaseProperties;
import com.womensafety.config.NotificationProperties;
import com.womensafety.config.PolicyProperties;
import com.womensafety.config.RateLimitProperties;
import com.womensafety.dto.AdminAlertDetail;
import com.womensafety.dto.AdminAlertSummary;
import com.womensafety.dto.AdminDashboardResponse;
import com.womensafety.dto.AdminSettingsResponse;
import com.womensafety.dto.AlertReportResponse;
import com.womensafety.dto.AuditLogResponse;
import com.womensafety.dto.DeliveryView;
import com.womensafety.dto.NotificationOverviewResponse;
import com.womensafety.exception.ConflictException;
import com.womensafety.exception.IdentityServiceException;
import com.womensafety.exception.InvalidRequestException;
import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.model.AccountStatus;
import com.womensafety.model.AlertEvent;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.IncidentReport;
import com.womensafety.model.ReportStatus;
import com.womensafety.model.UserProfile;
import com.womensafety.notification.SmsNotificationProvider;
import com.womensafety.repository.AdminDeviceRepository;
import com.womensafety.repository.DeliveryRepository;
import com.womensafety.repository.EmergencyAlertRepository;
import com.womensafety.repository.IncidentReportRepository;
import com.womensafety.repository.NotificationJobRepository;
import com.womensafety.repository.UserRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Administrator operations. Callers must already be authorised ({@code ROLE_ADMIN}, granted only to uids in the
 * server-side allow-list); this class additionally records an audit entry for every mutation and for every
 * disclosure of sensitive data (full phone number, alert coordinates, exports).
 *
 * <p>Honesty rule: acknowledging or resolving an alert only changes the stored record. It does not notify, dispatch or
 * contact anyone, and nothing here claims otherwise.</p>
 */
@Service
public class AdminService {

    private static final int USER_SCAN_LIMIT = 500;
    private static final int ALERT_SCAN_LIMIT = 1000;
    private static final int AUDIT_SCAN_LIMIT = 500;
    private static final int TREND_DAYS = 14;

    private final UserRepository users;
    private final EmergencyAlertRepository alerts;
    private final IncidentReportRepository reports;
    private final AuditService audit;
    private final IdentityAdminService identity;
    private final ReportService reportService;
    private final AdminProperties adminProperties;
    private final PolicyProperties policy;
    private final RateLimitProperties rateLimit;
    private final FirebaseProperties firebase;
    private final CorsProperties cors;
    private final Clock clock;
    private final AlertEventLog events;
    private final SosStreamService stream;
    private final DeliveryRepository deliveries;
    private final AdminDeviceRepository devices;
    private final NotificationJobRepository jobs;
    private final NotificationProperties notifications;
    private final SmsNotificationProvider sms;
    private final ObjectProvider<FirebaseMessaging> fcm;
    /** The dashboard issues a dozen independent Firestore queries; running them side by side keeps it responsive. */
    private final ExecutorService queryPool = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "admin-dashboard-query");
        thread.setDaemon(true);
        return thread;
    });

    public AdminService(UserRepository users, EmergencyAlertRepository alerts, IncidentReportRepository reports,
                        AuditService audit, IdentityAdminService identity, ReportService reportService,
                        AdminProperties adminProperties, PolicyProperties policy, RateLimitProperties rateLimit,
                        FirebaseProperties firebase, CorsProperties cors, Clock clock, AlertEventLog events,
                        SosStreamService stream, DeliveryRepository deliveries, AdminDeviceRepository devices,
                        NotificationJobRepository jobs, NotificationProperties notifications,
                        SmsNotificationProvider sms, ObjectProvider<FirebaseMessaging> fcm) {
        this.users = users;
        this.alerts = alerts;
        this.reports = reports;
        this.audit = audit;
        this.identity = identity;
        this.reportService = reportService;
        this.adminProperties = adminProperties;
        this.policy = policy;
        this.rateLimit = rateLimit;
        this.firebase = firebase;
        this.cors = cors;
        this.clock = clock;
        this.events = events;
        this.stream = stream;
        this.deliveries = deliveries;
        this.devices = devices;
        this.jobs = jobs;
        this.notifications = notifications;
        this.sms = sms;
        this.fcm = fcm;
    }

    /* ---------- dashboard ---------- */

    public AdminDashboardResponse dashboard(int utcOffsetMinutes) {
        Instant dayAgo = clock.instant().minus(Duration.ofHours(24));
        var totalUsers = async(users::countAll);
        var verifiedUsers = async(users::countPhoneVerified);
        var activeUsers = async(() -> users.countByStatus(AccountStatus.ACTIVE));
        var suspendedUsers = async(() -> users.countByStatus(AccountStatus.SUSPENDED));
        var totalAlerts = async(alerts::countAll);
        var alertsLast24Hours = async(() -> alerts.countCreatedSince(dayAgo));
        var alertsNew = async(() -> alerts.countByStatus(AlertStatus.ACTIVE));
        var alertsAcknowledged = async(() -> alerts.countByStatus(AlertStatus.ACKNOWLEDGED));
        var alertsResolved = async(() -> alerts.countByStatus(AlertStatus.RESOLVED));
        var alertsCancelled = async(() -> alerts.countByStatus(AlertStatus.CANCELLED));
        var openReports = async(() -> reports.countByStatus(ReportStatus.OPEN));
        var registrations = async(() -> reportService.registrationsByDay(TREND_DAYS, utcOffsetMinutes));
        var alertTrend = async(() -> reportService.alertsByDay(TREND_DAYS, utcOffsetMinutes));
        var recentAlerts = async(() -> alerts.findRecent(5).stream().map(this::summary).toList());
        var recentAudit = async(() -> audit.recent(5).stream().map(AuditLogResponse::from).toList());
        return new AdminDashboardResponse(
                await(totalUsers), await(verifiedUsers), await(activeUsers), await(suspendedUsers),
                await(totalAlerts), await(alertsLast24Hours), await(alertsNew), await(alertsAcknowledged),
                await(alertsResolved), await(alertsCancelled), await(openReports),
                await(registrations), await(alertTrend), await(recentAlerts), await(recentAudit));
    }

    private <T> CompletableFuture<T> async(Supplier<T> query) {
        return CompletableFuture.supplyAsync(query, queryPool);
    }

    /** Waits for a query and rethrows its original exception so the usual error handling still applies. */
    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw e;
        }
    }

    @PreDestroy
    void shutdownQueryPool() {
        queryPool.shutdown();
    }

    /* ---------- users ---------- */

    /**
     * Newest accounts first. Search and status filter are applied to the newest {@value #USER_SCAN_LIMIT} accounts,
     * which is ample for development; a production-scale search needs indexed queries.
     */
    public List<UserProfile> listUsers(String search, AccountStatus status) {
        String needle = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        String digits = needle.replaceAll("\\D", "");
        return users.findRecent(USER_SCAN_LIMIT).stream()
                .filter(u -> status == null || u.accountStatus() == status)
                .filter(u -> needle.isEmpty()
                        || nullToEmpty(u.fullName()).toLowerCase(Locale.ROOT).contains(needle)
                        || u.uid().equalsIgnoreCase(needle)
                        || (!digits.isEmpty() && nullToEmpty(u.phoneNumber()).replaceAll("\\D", "").contains(digits)))
                .toList();
    }

    /** Full profile details (including the complete phone number). The access is audited. */
    public UserProfile viewUser(String adminUid, String targetUid) {
        UserProfile profile = users.findByUid(targetUid).orElseThrow(AdminService::userNotFound);
        audit.recordAdminAction(adminUid, "VIEW_USER", targetUid, Map.of());
        return profile;
    }

    public UserProfile changeStatus(String adminUid, String targetUid, AccountStatus newStatus, String reason) {
        if (adminProperties.isAdmin(targetUid)) {
            throw new ConflictException("ADMIN_PROTECTED", "Administrator accounts cannot be suspended.");
        }
        UserProfile profile = users.findByUid(targetUid).orElseThrow(AdminService::userNotFound);
        if (profile.accountStatus() == newStatus) {
            return profile;
        }
        boolean suspend = newStatus == AccountStatus.SUSPENDED;
        String action = suspend ? "SUSPEND_USER" : "REACTIVATE_USER";
        Map<String, String> details = reason == null || reason.isBlank() ? Map.of() : Map.of("reason", reason.strip());

        // The Firestore status is what the API enforces, so it is written first; Firebase is then updated so the
        // account cannot sign in or refresh tokens either.
        UserProfile updated = users.save(profile.withAccountStatus(newStatus, now()));
        try {
            identity.setDisabled(targetUid, suspend);
            if (suspend) {
                identity.revokeSessions(targetUid);
            }
        } catch (IdentityServiceException e) {
            audit.recordFailedAdminAction(adminUid, action, targetUid, Map.of("problem", "Firebase account update failed"));
            throw e;
        }
        audit.recordAdminAction(adminUid, action, targetUid, details);
        return updated;
    }

    /* ---------- alerts ---------- */

    public AdminAlertSummary summary(EmergencyAlert alert) {
        return AdminAlertSummary.from(alert, clock.instant(), notifications.attentionMinutes());
    }

    /**
     * Alerts, newest first, optionally limited to a status, to creation dates (inclusive, in the caller's UTC offset)
     * and to an alert id (prefix or part of the id). Reads at most {@value #ALERT_SCAN_LIMIT} records.
     */
    public List<EmergencyAlert> listAlerts(AlertStatus status, LocalDate from, LocalDate to, int utcOffsetMinutes, String idQuery) {
        ZoneOffset zone = ReportService.zone(utcOffsetMinutes);
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidRequestException("from", "The start date must not be after the end date.");
        }
        String needle = idQuery == null ? "" : idQuery.strip().toLowerCase(Locale.ROOT);
        List<EmergencyAlert> found;
        if (from == null && to == null) {
            found = alerts.findRecent(ALERT_SCAN_LIMIT);
        } else {
            Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(zone).toInstant();
            Instant end = to == null ? clock.instant().plus(Duration.ofDays(1)) : to.plusDays(1).atStartOfDay(zone).toInstant();
            found = alerts.findCreatedBetween(start, end, ALERT_SCAN_LIMIT);
        }
        return found.stream()
                .filter(a -> status == null || a.status() == status)
                .filter(a -> needle.isEmpty() || a.id().toLowerCase(Locale.ROOT).contains(needle))
                .sorted(Comparator.comparing(EmergencyAlert::createdAt).reversed())
                .toList();
    }

    /** Alert detail including coordinates and history. The disclosure is audited BEFORE the data is returned. */
    public AdminAlertDetail viewAlertDetail(String adminUid, String alertId) {
        EmergencyAlert alert = findAlert(alertId);
        audit.recordAdminAction(adminUid, "VIEW_ALERT", alert.uid(),
                Map.of("alertId", alertId, "locationDisclosed", String.valueOf(alert.location() != null)));
        return AdminAlertDetail.from(alert, events.history(alertId), clock.instant(), notifications.attentionMinutes());
    }

    /** Recipient-level delivery records (recipients are masked). Opening them is audited. */
    public List<DeliveryView> alertDeliveries(String adminUid, String alertId) {
        EmergencyAlert alert = findAlert(alertId);
        audit.recordAdminAction(adminUid, "VIEW_ALERT_DELIVERIES", alert.uid(), Map.of("alertId", alertId));
        return deliveries.findAll(alertId).stream().map(DeliveryView::from).toList();
    }

    /**
     * The one entry point for status changes (PATCH /sos/{id}/status). Allowed: ACTIVE → ACKNOWLEDGED, and ACTIVE or
     * ACKNOWLEDGED → RESOLVED (note required). Nothing can reopen a resolved or cancelled alert.
     */
    public EmergencyAlert changeAlertStatus(String adminUid, String alertId, AlertStatus target, String note) {
        return switch (target) {
            case ACKNOWLEDGED -> acknowledgeAlert(adminUid, alertId);
            case RESOLVED -> {
                if (note == null || note.strip().length() < 3) {
                    throw new InvalidRequestException("note", "Add a resolution note of at least 3 characters.");
                }
                yield resolveAlert(adminUid, alertId, note);
            }
            default -> throw new InvalidRequestException("status", "Status can only be changed to ACKNOWLEDGED or RESOLVED.");
        };
    }

    /** NEW → ACKNOWLEDGED. Means "an administrator has seen this record"; nothing is sent to anyone. */
    public EmergencyAlert acknowledgeAlert(String adminUid, String alertId) {
        Instant now = now();
        EmergencyAlert saved = alerts.modify(alertId, alert -> {
            if (alert.status() != AlertStatus.ACTIVE) {
                throw new ConflictException("ALERT_NOT_NEW", "Only a new alert can be acknowledged; this one is " + alert.status().name().toLowerCase(Locale.ROOT) + ".");
            }
            return alert.acknowledged(adminUid, now);
        });
        audit.recordAdminAction(adminUid, "ACKNOWLEDGE_ALERT", saved.uid(), Map.of("alertId", alertId));
        events.record(alertId, AlertEvent.ACKNOWLEDGED, adminUid, Map.of());
        stream.publish("acknowledged", alertId, saved.status().name());
        return saved;
    }

    /** NEW or ACKNOWLEDGED → RESOLVED with a mandatory note. */
    public EmergencyAlert resolveAlert(String adminUid, String alertId, String note) {
        Instant now = now();
        String cleanNote = note.strip();
        EmergencyAlert saved = alerts.modify(alertId, alert -> {
            if (alert.status() != AlertStatus.ACTIVE && alert.status() != AlertStatus.ACKNOWLEDGED) {
                throw new ConflictException("ALERT_NOT_OPEN", "Only a new or acknowledged alert can be resolved; this one is " + alert.status().name().toLowerCase(Locale.ROOT) + ".");
            }
            return alert.resolved(adminUid, cleanNote, now);
        });
        // The note itself stays on the alert record; the audit entry and history only reference the alert.
        audit.recordAdminAction(adminUid, "RESOLVE_ALERT", saved.uid(), Map.of("alertId", alertId));
        events.record(alertId, AlertEvent.RESOLVED, adminUid, Map.of());
        stream.publish("resolved", alertId, saved.status().name());
        return saved;
    }

    /* ---------- notifications overview ---------- */

    public NotificationOverviewResponse notificationOverview(String adminUid) {
        List<NotificationOverviewResponse.Problem> problems = alerts.findRecent(50).stream()
                .filter(a -> a.contactSummary().needsAttention()
                        || a.adminSummary().state() == com.womensafety.model.NotificationSummary.State.FAILED)
                .limit(10)
                .map(a -> new NotificationOverviewResponse.Problem(a.id(), a.createdAt(), a.contactSummary().state().name(),
                        a.adminSummary().state().name()))
                .toList();
        boolean test = !notifications.realMode();
        return new NotificationOverviewResponse(notifications.mode().name(), sms.name(), fcm.getIfAvailable() != null,
                notifications.adminPushEnabled(), devices.findAll().size(), devices.findByUid(adminUid).size(),
                jobs.countOpen(), notificationNote(), problems);
    }

    private String notificationNote() {
        String base = notifications.realMode()
                ? "SMS messages are sent through " + sms.name() + ". A message is only shown as delivered when the provider reports it."
                : "TEST MODE: SMS messages are simulated and nothing is sent to anyone.";
        return base + " SafeHer does not contact the police or any emergency service, and no staffed response service is configured.";
    }

    /* ---------- incident reports ---------- */

    public List<IncidentReport> listReports(int limit) {
        return reports.findRecent(limit);
    }

    public IncidentReport resolveReport(String adminUid, String reportId, String note) {
        IncidentReport report = reports.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("That report does not exist."));
        if (report.status() == ReportStatus.RESOLVED) {
            throw new ConflictException("REPORT_ALREADY_RESOLVED", "This report is already resolved.");
        }
        IncidentReport saved = reports.save(report.resolved(adminUid, note == null ? "" : note.strip(), now()));
        audit.recordAdminAction(adminUid, "RESOLVE_REPORT", report.uid(), Map.of("reportId", reportId));
        return saved;
    }

    /* ---------- reports and exports ---------- */

    public AlertReportResponse alertReport(ReportService.Period period, LocalDate from, LocalDate to, int utcOffsetMinutes) {
        return reportService.alertReport(period, from, to, utcOffsetMinutes);
    }

    /** Builds the CSV and records the export in the audit log before returning it. */
    public String exportAlertReportCsv(String adminUid, ReportService.Period period, LocalDate from, LocalDate to,
                                       int utcOffsetMinutes) {
        AlertReportResponse report = reportService.alertReport(period, from, to, utcOffsetMinutes);
        audit.recordAdminAction(adminUid, "EXPORT_ALERT_REPORT", null,
                Map.of("period", report.period(), "from", report.from(), "to", report.to()));
        return reportService.toCsv(report);
    }

    /* ---------- audit log and settings ---------- */

    /** Newest first, optionally only one type ("ADMIN_ACTION" or "SECURITY_EVENT"). */
    public List<AuditLogResponse> auditLogs(String type) {
        return audit.recent(AUDIT_SCAN_LIMIT).stream()
                .filter(e -> type == null || type.equals(e.type()))
                .map(AuditLogResponse::from)
                .toList();
    }

    public AdminSettingsResponse settings() {
        return new AdminSettingsResponse(
                adminProperties.adminUidSet().size(),
                policy.alertRetentionDays(),
                policy.consentVersion(),
                rateLimit.enabled(),
                rateLimit.authPerMinute(),
                rateLimit.generalPerMinute(),
                policy.maxAlertsPerWindow(),
                policy.alertWindowMinutes(),
                firebase.checkRevoked(),
                cors.allowedOrigins(),
                sms.name(),
                notificationNote(),
                notifications.mode().name(),
                policy.maxEmergencyContacts(),
                notifications.maxAttempts(),
                notifications.adminPushEnabled());
    }

    public boolean isAdministrator(String uid) {
        return adminProperties.isAdmin(uid);
    }

    /* ---------- helpers ---------- */

    private EmergencyAlert findAlert(String alertId) {
        return alerts.findById(alertId).orElseThrow(() -> new ResourceNotFoundException("That alert does not exist."));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ResourceNotFoundException userNotFound() {
        return new ResourceNotFoundException("That user does not exist.");
    }
}
