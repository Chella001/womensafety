package com.womensafety.repository;

import java.util.List;
import java.util.Optional;

import com.womensafety.model.IncidentReport;
import com.womensafety.model.ReportStatus;

public interface IncidentReportRepository {

    IncidentReport save(IncidentReport report);

    Optional<IncidentReport> findById(String reportId);

    /** All reports of one user (unordered; callers sort). */
    List<IncidentReport> findByUid(String uid);

    /** Newest reports first. */
    List<IncidentReport> findRecent(int limit);

    long countByStatus(ReportStatus status);
}
