package com.womensafety.dto;

/**
 * Alert counts for one reporting period. Counts are of alert RECORDS created in the period, split by their CURRENT
 * status. No personal data is included, which is why this report may be exported.
 */
public record ReportRow(
        String periodStart,
        String label,
        long total,
        long newAlerts,
        long acknowledged,
        long resolved,
        long cancelled,
        long withLocation) {
}
