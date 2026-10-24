package com.womensafety.dto;

import java.util.List;
import java.util.function.Function;

/** One page of a list. {@code total} is the number of items matching the filters (not just this page). */
public record PageResponse<T>(List<T> items, int page, int size, long total, int totalPages) {

    /** Slices an already filtered and ordered list. {@code page} is zero-based. */
    public static <S, T> PageResponse<T> of(List<S> all, int page, int size, Function<S, T> mapper) {
        int from = (int) Math.min((long) page * size, all.size());
        int to = Math.min(from + size, all.size());
        int pages = all.isEmpty() ? 0 : (int) Math.ceil(all.size() / (double) size);
        return new PageResponse<>(all.subList(from, to).stream().map(mapper).toList(), page, size, all.size(), pages);
    }
}
