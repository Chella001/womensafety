package com.womensafety.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.womensafety.model.AlertLocation;

/**
 * Text of the messages SafeHer sends. They contain the least personal data that still lets the recipient act: the
 * user's first name, the time, and the location only when the user chose to share it.
 */
final class SosMessages {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private SosMessages() {
    }

    static String emergency(String fullName, Instant createdAt, AlertLocation location) {
        StringBuilder text = new StringBuilder("SafeHer SOS: ").append(firstName(fullName))
                .append(" pressed the SOS button at ").append(TIME.format(createdAt)).append(". Please try to reach them now.");
        if (location != null) {
            text.append(" Location: https://maps.google.com/?q=")
                    .append(String.format(Locale.ROOT, "%.5f,%.5f", location.latitude(), location.longitude()));
            if (location.accuracyMeters() != null) {
                text.append(" (about ").append(Math.round(location.accuracyMeters())).append(" m)");
            }
            text.append('.');
        }
        return text.append(" If they may be in danger, call your local emergency number.").toString();
    }

    static String verification(String ownerName, String code, int validMinutes) {
        return "SafeHer: " + firstName(ownerName) + " wants to add you as an emergency contact. Your confirmation code is "
                + code + " (valid " + validMinutes + " min). Only share it if you agree. Ignore this message if you do not know them.";
    }

    static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "A SafeHer user";
        }
        String first = fullName.strip().split("\\s+")[0];
        return first.length() > 30 ? first.substring(0, 30) : first;
    }
}
