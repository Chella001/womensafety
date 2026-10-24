package com.womensafety.controller;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.womensafety.dto.AdminAlertDetail;
import com.womensafety.dto.AdminAlertSummary;
import com.womensafety.dto.AdminDashboardResponse;
import com.womensafety.dto.AdminSettingsResponse;
import com.womensafety.dto.AdminUserDetail;
import com.womensafety.dto.AdminUserSummary;
import com.womensafety.dto.AlertReportResponse;
import com.womensafety.dto.AuditLogResponse;
import com.womensafety.dto.ChangeAlertStatusRequest;
import com.womensafety.dto.DeliveryView;
import com.womensafety.dto.DeviceResponse;
import com.womensafety.dto.IncidentReportResponse;
import com.womensafety.dto.NotificationOverviewResponse;
import com.womensafety.dto.PageResponse;
import com.womensafety.dto.RegisterDeviceRequest;
import com.womensafety.dto.ResolveAlertRequest;
import com.womensafety.dto.ResolveReportRequest;
import com.womensafety.dto.UpdateAccountStatusRequest;
import com.womensafety.exception.InvalidRequestException;
import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.model.AccountStatus;
import com.womensafety.model.AlertStatus;
import com.womensafety.security.FirebaseUserPrincipal;
import com.womensafety.service.AdminDeviceService;
import com.womensafety.service.AdminService;
import com.womensafety.service.ReportService;
import com.womensafety.service.SosStreamService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Administrator API. Access is enforced twice: {@code SecurityConfig} requires {@code ROLE_ADMIN} for
 * {@code /api/admin/**}, and that authority is granted only to uids in the server-side allow-list.
 * Lists are paged ({@code page} is zero-based, {@code size} is 1–100).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String UID_PATTERN = "^[A-Za-z0-9_-]{1,128}$";

    private final AdminService service;
    private final AdminDeviceService deviceService;
    private final SosStreamService stream;

    public AdminController(AdminService service, AdminDeviceService deviceService, SosStreamService stream) {
        this.service = service;
        this.deviceService = deviceService;
        this.stream = stream;
    }

    /* ---------- dashboard ---------- */

    @GetMapping("/dashboard")
    public ResponseEntity<AdminDashboardResponse> dashboard(@RequestParam(defaultValue = "0") int utcOffsetMinutes) {
        return noStore(service.dashboard(utcOffsetMinutes));
    }

    /* ---------- users ---------- */

    @GetMapping("/users")
    public ResponseEntity<PageResponse<AdminUserSummary>> users(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        String cleanSearch = search == null ? null : search.substring(0, Math.min(search.length(), 80));
        return noStore(PageResponse.of(service.listUsers(cleanSearch, status), page(page), size(size), AdminUserSummary::from));
    }

    @GetMapping("/users/{uid}")
    public ResponseEntity<AdminUserDetail> user(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String uid) {
        requireUid(uid);
        return noStore(AdminUserDetail.from(service.viewUser(admin.uid(), uid), service.isAdministrator(uid)));
    }

    @PatchMapping("/users/{uid}/status")
    public ResponseEntity<AdminUserDetail> changeStatus(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String uid,
            @Valid @RequestBody UpdateAccountStatusRequest request) {
        requireUid(uid);
        return noStore(AdminUserDetail.from(
                service.changeStatus(admin.uid(), uid, request.status(), request.reason()), service.isAdministrator(uid)));
    }

    /* ---------- SOS alerts (/sos is the primary path, /alerts the original alias) ---------- */

    @GetMapping({"/sos", "/alerts"})
    public ResponseEntity<PageResponse<AdminAlertSummary>> alerts(
            @RequestParam(required = false) AlertStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int utcOffsetMinutes,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        String idQuery = q == null ? null : q.substring(0, Math.min(q.length(), 64));
        return noStore(PageResponse.of(service.listAlerts(status, from, to, utcOffsetMinutes, idQuery), page(page), size(size), service::summary));
    }

    /**
     * Live signal stream (Server-Sent Events). It carries only "something changed" markers; the console reloads data
     * through the normal authorised endpoints. Closes after a few minutes so the client reconnects with a fresh token.
     */
    @GetMapping(value = "/sos/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return stream.subscribe();
    }

    @GetMapping({"/sos/{alertId}", "/alerts/{alertId}"})
    public ResponseEntity<AdminAlertDetail> alert(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String alertId) {
        requireId(alertId);
        return noStore(service.viewAlertDetail(admin.uid(), alertId));
    }

    @GetMapping({"/sos/{alertId}/deliveries", "/alerts/{alertId}/deliveries"})
    public ResponseEntity<List<DeliveryView>> deliveries(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String alertId) {
        requireId(alertId);
        return noStore(service.alertDeliveries(admin.uid(), alertId));
    }

    /** ACTIVE → ACKNOWLEDGED, or ACTIVE/ACKNOWLEDGED → RESOLVED (with a note). Nothing is sent to anyone. */
    @PatchMapping("/sos/{alertId}/status")
    public ResponseEntity<AdminAlertSummary> changeAlertStatus(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String alertId,
            @Valid @RequestBody ChangeAlertStatusRequest request) {
        requireId(alertId);
        return noStore(service.summary(service.changeAlertStatus(admin.uid(), alertId, request.status(), request.note())));
    }

    /** Marks a new alert as seen by an administrator. Notifies nobody. */
    @PostMapping({"/sos/{alertId}/acknowledge", "/alerts/{alertId}/acknowledge"})
    public ResponseEntity<AdminAlertSummary> acknowledge(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String alertId) {
        requireId(alertId);
        return noStore(service.summary(service.acknowledgeAlert(admin.uid(), alertId)));
    }

    /** Closes an alert with a mandatory note. Notifies nobody. */
    @PostMapping({"/sos/{alertId}/resolve", "/alerts/{alertId}/resolve"})
    public ResponseEntity<AdminAlertSummary> resolve(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String alertId,
            @Valid @RequestBody ResolveAlertRequest request) {
        requireId(alertId);
        return noStore(service.summary(service.resolveAlert(admin.uid(), alertId, request.note())));
    }

    /* ---------- notifications and administrator push devices ---------- */

    @GetMapping("/notifications/overview")
    public ResponseEntity<NotificationOverviewResponse> notificationOverview(@AuthenticationPrincipal FirebaseUserPrincipal admin) {
        return noStore(service.notificationOverview(admin.uid()));
    }

    @GetMapping("/devices")
    public ResponseEntity<List<DeviceResponse>> devices(@AuthenticationPrincipal FirebaseUserPrincipal admin) {
        return noStore(deviceService.listOwn(admin.uid()).stream().map(DeviceResponse::from).toList());
    }

    @PostMapping("/devices")
    public ResponseEntity<DeviceResponse> registerDevice(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @Valid @RequestBody RegisterDeviceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(DeviceResponse.from(deviceService.register(admin.uid(), request.token(), request.label())));
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<Void> removeDevice(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String deviceId) {
        if (!deviceId.matches("^[a-f0-9]{64}$")) {
            throw new ResourceNotFoundException("That device does not exist.");
        }
        deviceService.remove(admin.uid(), deviceId);
        return ResponseEntity.noContent().build();
    }

    /* ---------- incident reports ---------- */

    @GetMapping("/incident-reports")
    public ResponseEntity<List<IncidentReportResponse>> incidentReports(@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        return noStore(service.listReports(clamp(limit)).stream().map(IncidentReportResponse::from).toList());
    }

    @PatchMapping("/incident-reports/{reportId}/resolve")
    public ResponseEntity<IncidentReportResponse> resolveIncidentReport(
            @AuthenticationPrincipal FirebaseUserPrincipal admin, @PathVariable String reportId,
            @Valid @RequestBody ResolveReportRequest request) {
        requireId(reportId);
        return noStore(IncidentReportResponse.from(service.resolveReport(admin.uid(), reportId, request.note())));
    }

    /* ---------- summaries and export ---------- */

    @GetMapping("/reports/alerts")
    public ResponseEntity<AlertReportResponse> alertReport(
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int utcOffsetMinutes) {
        return noStore(service.alertReport(period(period), from, to, utcOffsetMinutes));
    }

    /** CSV of the same summary (dates and counts only). The export is written to the audit log. */
    @GetMapping(value = "/reports/alerts.csv", produces = "text/csv")
    public ResponseEntity<String> alertReportCsv(
            @AuthenticationPrincipal FirebaseUserPrincipal admin,
            @RequestParam(defaultValue = "daily") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int utcOffsetMinutes) {
        ReportService.Period parsed = period(period);
        String csv = service.exportAlertReportCsv(admin.uid(), parsed, from, to, utcOffsetMinutes);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("safeher-alert-summary-" + parsed.name().toLowerCase(Locale.ROOT) + ".csv").build().toString())
                .body(csv);
    }

    /* ---------- audit log and settings (read-only) ---------- */

    @GetMapping("/audit-logs")
    public ResponseEntity<PageResponse<AuditLogResponse>> auditLogs(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        String cleanType = null;
        if (type != null && !type.isBlank()) {
            cleanType = type.strip().toUpperCase(Locale.ROOT);
            if (!cleanType.equals("ADMIN_ACTION") && !cleanType.equals("SECURITY_EVENT") && !cleanType.equals("SYSTEM_EVENT")) {
                throw new InvalidRequestException("type", "Type must be ADMIN_ACTION, SECURITY_EVENT or SYSTEM_EVENT.");
            }
        }
        return noStore(PageResponse.of(service.auditLogs(cleanType), page(page), size(size), entry -> entry));
    }

    @GetMapping("/settings")
    public ResponseEntity<AdminSettingsResponse> settings() {
        return noStore(service.settings());
    }

    /* ---------- helpers ---------- */

    private static ReportService.Period period(String value) {
        try {
            return ReportService.Period.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("period", "Period must be daily, weekly or monthly.");
        }
    }

    private static int page(int page) {
        return Math.max(page, 0);
    }

    private static int size(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private static void requireUid(String uid) {
        if (!uid.matches(UID_PATTERN)) {
            throw new ResourceNotFoundException("That user does not exist.");
        }
    }

    private static void requireId(String id) {
        if (!id.matches("^[A-Za-z0-9-]{1,64}$")) {
            throw new ResourceNotFoundException("That record does not exist.");
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
