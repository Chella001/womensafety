package com.womensafety.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.womensafety.dto.AlertReportResponse;
import com.womensafety.dto.DailyPoint;
import com.womensafety.dto.ReportRow;
import com.womensafety.exception.InvalidRequestException;
import com.womensafety.model.AlertStatus;
import com.womensafety.model.EmergencyAlert;
import com.womensafety.model.UserProfile;
import com.womensafety.repository.EmergencyAlertRepository;
import com.womensafety.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Trends and period summaries computed from real Firestore records. Days are cut in the UTC offset the caller supplies
 * (the browser's offset), so "today" matches what the administrator sees on their clock.
 */
@Service
public class ReportService {

    public enum Period { DAILY, WEEKLY, MONTHLY }

    /** Upper bound on documents read for one report; when it is reached the response says so ({@code truncated}). */
    static final int READ_LIMIT = 5000;
    static final int MAX_RANGE_DAYS = 731;
    static final int MAX_OFFSET_MINUTES = 14 * 60;

    private final EmergencyAlertRepository alerts;
    private final UserRepository users;
    private final Clock clock;

    public ReportService(EmergencyAlertRepository alerts, UserRepository users, Clock clock) {
        this.alerts = alerts;
        this.users = users;
        this.clock = clock;
    }

    /** Alert records created per day for the last {@code days} days (zero days included). */
    public List<DailyPoint> alertsByDay(int days, int offsetMinutes) {
        ZoneOffset zone = zone(offsetMinutes);
        LocalDate start = today(zone).minusDays(days - 1L);
        List<Instant> created = alerts.findCreatedBetween(startOf(start, zone), startOf(today(zone).plusDays(1), zone), READ_LIMIT)
                .stream().map(EmergencyAlert::createdAt).toList();
        return series(created, start, days, zone);
    }

    /** Accounts registered per day for the last {@code days} days (zero days included). */
    public List<DailyPoint> registrationsByDay(int days, int offsetMinutes) {
        ZoneOffset zone = zone(offsetMinutes);
        LocalDate start = today(zone).minusDays(days - 1L);
        List<Instant> created = users.findCreatedSince(startOf(start, zone), READ_LIMIT)
                .stream().map(UserProfile::createdAt).toList();
        return series(created, start, days, zone);
    }

    public AlertReportResponse alertReport(Period period, LocalDate from, LocalDate to, int offsetMinutes) {
        ZoneOffset zone = zone(offsetMinutes);
        LocalDate end = to != null ? to : today(zone);
        LocalDate begin = from != null ? from : defaultStart(period, end);
        if (begin.isAfter(end)) {
            throw new InvalidRequestException("from", "The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(begin, end) > MAX_RANGE_DAYS) {
            throw new InvalidRequestException("from", "The date range can span at most two years.");
        }
        // Widen to whole periods so the first and last rows are complete.
        LocalDate firstBucket = bucketStart(period, begin);
        LocalDate afterLast = nextBucket(period, bucketStart(period, end));

        List<EmergencyAlert> found = alerts.findCreatedBetween(startOf(firstBucket, zone), startOf(afterLast, zone), READ_LIMIT);
        Map<LocalDate, long[]> counts = new LinkedHashMap<>();
        for (LocalDate b = firstBucket; b.isBefore(afterLast); b = nextBucket(period, b)) {
            counts.put(b, new long[6]);
        }
        for (EmergencyAlert alert : found) {
            long[] c = counts.get(bucketStart(period, alert.createdAt().atZone(zone).toLocalDate()));
            if (c == null) {
                continue;
            }
            c[0]++;
            switch (alert.status()) {
                case ACTIVE -> c[1]++;
                case ACKNOWLEDGED -> c[2]++;
                case RESOLVED -> c[3]++;
                case CANCELLED -> c[4]++;
            }
            if (alert.location() != null) {
                c[5]++;
            }
        }
        List<ReportRow> rows = new ArrayList<>();
        counts.forEach((start, c) -> rows.add(new ReportRow(start.toString(), label(period, start), c[0], c[1], c[2], c[3], c[4], c[5])));
        return new AlertReportResponse(period.name().toLowerCase(), firstBucket.toString(),
                afterLast.minusDays(1).toString(), rows, found.size() >= READ_LIMIT);
    }

    /** CSV of the summary. It contains only dates and counts, so there is nothing personal (or injectable) in it. */
    public String toCsv(AlertReportResponse report) {
        StringBuilder csv = new StringBuilder("period,period_start,total_alerts,new,acknowledged,resolved,cancelled,location_shared\r\n");
        for (ReportRow r : report.rows()) {
            csv.append(report.period()).append(',').append(r.periodStart()).append(',').append(r.total()).append(',')
                    .append(r.newAlerts()).append(',').append(r.acknowledged()).append(',').append(r.resolved()).append(',')
                    .append(r.cancelled()).append(',').append(r.withLocation()).append("\r\n");
        }
        return csv.toString();
    }

    /* ---------- helpers ---------- */

    private static List<DailyPoint> series(List<Instant> created, LocalDate start, int days, ZoneOffset zone) {
        long[] counts = new long[days];
        for (Instant at : created) {
            long index = ChronoUnit.DAYS.between(start, at.atZone(zone).toLocalDate());
            if (index >= 0 && index < days) {
                counts[(int) index]++;
            }
        }
        List<DailyPoint> points = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            points.add(new DailyPoint(start.plusDays(i).toString(), counts[i]));
        }
        return points;
    }

    static LocalDate bucketStart(Period period, LocalDate date) {
        return switch (period) {
            case DAILY -> date;
            case WEEKLY -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTHLY -> date.withDayOfMonth(1);
        };
    }

    static LocalDate nextBucket(Period period, LocalDate bucketStart) {
        return switch (period) {
            case DAILY -> bucketStart.plusDays(1);
            case WEEKLY -> bucketStart.plusWeeks(1);
            case MONTHLY -> bucketStart.plusMonths(1);
        };
    }

    private static LocalDate defaultStart(Period period, LocalDate end) {
        return switch (period) {
            case DAILY -> end.minusDays(29);
            case WEEKLY -> end.minusWeeks(11);
            case MONTHLY -> end.minusMonths(11);
        };
    }

    private static String label(Period period, LocalDate start) {
        return switch (period) {
            case DAILY -> start.toString();
            case WEEKLY -> "Week of " + start;
            case MONTHLY -> start.toString().substring(0, 7);
        };
    }

    private static Instant startOf(LocalDate date, ZoneOffset zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    private LocalDate today(ZoneOffset zone) {
        return clock.instant().atZone(zone).toLocalDate();
    }

    static ZoneOffset zone(int offsetMinutes) {
        if (Math.abs(offsetMinutes) > MAX_OFFSET_MINUTES) {
            throw new InvalidRequestException("utcOffsetMinutes", "The UTC offset must be between -840 and 840 minutes.");
        }
        return ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
    }
}
