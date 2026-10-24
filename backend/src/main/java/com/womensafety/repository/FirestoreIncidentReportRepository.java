package com.womensafety.repository;

import static com.womensafety.repository.FirestoreSupport.await;
import static com.womensafety.repository.FirestoreSupport.toInstant;
import static com.womensafety.repository.FirestoreSupport.toTimestamp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.womensafety.model.IncidentReport;
import com.womensafety.model.ReportStatus;
import org.springframework.stereotype.Repository;

@Repository
public class FirestoreIncidentReportRepository implements IncidentReportRepository {

    static final String COLLECTION = "incidentReports";

    private final Firestore firestore;

    public FirestoreIncidentReportRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public IncidentReport save(IncidentReport report) {
        await(reports().document(report.id()).set(toDocument(report)));
        return report;
    }

    @Override
    public Optional<IncidentReport> findById(String reportId) {
        DocumentSnapshot snapshot = await(reports().document(reportId).get());
        return snapshot.exists() ? Optional.of(toReport(snapshot)) : Optional.empty();
    }

    @Override
    public List<IncidentReport> findByUid(String uid) {
        return await(reports().whereEqualTo("uid", uid).get()).getDocuments().stream()
                .map(FirestoreIncidentReportRepository::toReport).toList();
    }

    @Override
    public List<IncidentReport> findRecent(int limit) {
        return await(reports().orderBy("createdAt", Query.Direction.DESCENDING).limit(limit).get()).getDocuments()
                .stream().map(FirestoreIncidentReportRepository::toReport).toList();
    }

    @Override
    public long countByStatus(ReportStatus status) {
        return FirestoreSupport.count(reports().whereEqualTo("status", status.name()));
    }

    private CollectionReference reports() {
        return firestore.collection(COLLECTION);
    }

    static Map<String, Object> toDocument(IncidentReport r) {
        Map<String, Object> d = new HashMap<>();
        d.put("uid", r.uid());
        d.put("category", r.category());
        d.put("description", r.description());
        d.put("occurredAt", toTimestamp(r.occurredAt()));
        d.put("status", r.status().name());
        d.put("createdAt", toTimestamp(r.createdAt()));
        d.put("resolvedAt", toTimestamp(r.resolvedAt()));
        d.put("resolvedBy", r.resolvedBy());
        d.put("resolutionNote", r.resolutionNote());
        return d;
    }

    static IncidentReport toReport(DocumentSnapshot s) {
        return new IncidentReport(
                s.getId(),
                s.getString("uid"),
                s.getString("category"),
                s.getString("description"),
                toInstant(s.getTimestamp("occurredAt")),
                FirestoreUserRepository.parse(ReportStatus.class, s.getString("status"), ReportStatus.OPEN),
                toInstant(s.getTimestamp("createdAt")),
                toInstant(s.getTimestamp("resolvedAt")),
                s.getString("resolvedBy"),
                s.getString("resolutionNote"));
    }
}
