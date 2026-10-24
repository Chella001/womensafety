package com.womensafety.repository;

import java.time.Instant;
import java.util.concurrent.ExecutionException;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.AlreadyExistsException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.AggregateQuery;
import com.google.cloud.firestore.Query;
import com.womensafety.exception.DataStoreException;

/** Small helpers shared by the Firestore repositories. */
final class FirestoreSupport {

    private FirestoreSupport() {
    }

    static <T> T await(ApiFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataStoreException("Interrupted while waiting for Firestore", e);
        } catch (ExecutionException e) {
            throw new DataStoreException("Firestore request failed", e);
        }
    }

    /**
     * Like {@link #await} for transactions whose callback may throw one of OUR exceptions (not found, conflict…):
     * those are rethrown as they are instead of being reported as a data-store failure.
     */
    static <T> T awaitTransaction(ApiFuture<T> future) {
        try {
            return await(future);
        } catch (DataStoreException e) {
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof RuntimeException runtime && cause.getClass().getName().startsWith("com.womensafety.exception.")
                        && !(cause instanceof DataStoreException)) {
                    throw runtime;
                }
            }
            throw e;
        }
    }

    static boolean isAlreadyExists(DataStoreException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof AlreadyExistsException) {
                return true;
            }
        }
        return false;
    }

    static long count(Query query) {
        AggregateQuery aggregate = query.count();
        return await(aggregate.get()).getCount();
    }

    static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }
}
