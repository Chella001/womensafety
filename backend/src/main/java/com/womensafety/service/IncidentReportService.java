package com.womensafety.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.womensafety.dto.IncidentReportRequest;
import com.womensafety.exception.InvalidRequestException;
import com.womensafety.exception.TooManyRequestsException;
import com.womensafety.model.IncidentReport;
import com.womensafety.model.ReportStatus;
import com.womensafety.repository.IncidentReportRepository;
import org.springframework.stereotype.Service;

@Service
public class IncidentReportService {

    private static final int MAX_REPORTS_PER_HOUR = 5;
    private static final int HISTORY_LIMIT = 50;

    private final IncidentReportRepository repository;
    private final Clock clock;

    public IncidentReportService(IncidentReportRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public IncidentReport create(String uid, IncidentReportRequest request) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (request.occurredAt() != null && request.occurredAt().isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new InvalidRequestException("occurredAt", "The incident time cannot be in the future.");
        }
        Instant hourAgo = now.minus(Duration.ofHours(1));
        long recent = repository.findByUid(uid).stream().filter(r -> r.createdAt().isAfter(hourAgo)).count();
        if (recent >= MAX_REPORTS_PER_HOUR) {
            throw new TooManyRequestsException("You have submitted several reports in the last hour. Please try again later.", 3600);
        }
        return repository.save(new IncidentReport(
                UUID.randomUUID().toString(), uid, request.category(), request.description().strip(),
                request.occurredAt(), ReportStatus.OPEN, now, null, null, null));
    }

    public List<IncidentReport> listOwn(String uid) {
        return repository.findByUid(uid).stream()
                .sorted(Comparator.comparing(IncidentReport::createdAt).reversed())
                .limit(HISTORY_LIMIT)
                .toList();
    }
}
