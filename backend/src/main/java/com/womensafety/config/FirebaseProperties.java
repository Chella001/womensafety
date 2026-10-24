package com.womensafety.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Firebase settings bound from {@code app.firebase.*}.
 *
 * @param projectId       Firebase project id; falls back to the one inside the credentials when blank
 * @param credentialsPath optional path to a service-account key; blank means Application Default Credentials
 * @param checkRevoked    whether ID tokens of revoked or disabled users must be rejected
 * @param firestoreDatabaseId Firestore database id; the standard database is {@code (default)}
 */
@ConfigurationProperties(prefix = "app.firebase")
public record FirebaseProperties(
        String projectId,
        String credentialsPath,
        @DefaultValue("true") boolean checkRevoked,
        @DefaultValue("(default)") String firestoreDatabaseId) {
}
