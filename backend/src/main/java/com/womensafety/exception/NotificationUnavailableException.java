package com.womensafety.exception;

/** The SMS provider could not take a message right now (verification codes are sent synchronously). Reported as 503. */
public class NotificationUnavailableException extends RuntimeException {

    public NotificationUnavailableException(String message) {
        super(message);
    }
}
