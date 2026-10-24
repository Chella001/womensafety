package com.womensafety.dto;

/** One day of a trend line. {@code date} is an ISO local date in the requested UTC offset. */
public record DailyPoint(String date, long count) {
}
