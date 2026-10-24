package com.womensafety.model;

/** Why an alert does or does not carry a location, so nobody has to guess from a missing value. */
public enum LocationStatus {
    /** Coordinates are stored on the alert. */
    AVAILABLE,
    /** The user chose not to attach a location. */
    NOT_REQUESTED,
    /** The browser/device permission was denied. */
    PERMISSION_DENIED,
    /** The device could not determine a position. */
    UNAVAILABLE,
    /** Determining the position took too long. */
    TIMEOUT
}
