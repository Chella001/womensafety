package com.womensafety.exception;

/** The request is valid but conflicts with the current state (duplicate, limit reached, wrong state…). */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String message) {
        this("CONFLICT", message);
    }

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
