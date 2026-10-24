package com.womensafety.exception;

/** Thrown when Firestore cannot be reached or rejects an operation. */
public class DataStoreException extends RuntimeException {

    public DataStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
