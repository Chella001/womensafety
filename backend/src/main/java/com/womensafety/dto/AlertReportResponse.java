package com.womensafety.dto;

import java.util.List;

/**
 * @param truncated true when more alerts exist in the range than the report reads (the figures are then a lower bound)
 */
public record AlertReportResponse(String period, String from, String to, List<ReportRow> rows, boolean truncated) {
}
