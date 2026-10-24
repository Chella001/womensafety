package com.womensafety.exception;

/** Firebase Authentication could not be reached or rejected an administrative operation. */
public class IdentityServiceException extends RuntimeException {

    public IdentityServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
