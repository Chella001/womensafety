package com.womensafety.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A STATIC check of {@code firestore.rules}: every browser read and write must be denied, because all data access goes
 * through the backend (Admin SDK, which bypasses rules). This does not run the rules engine; the rules are also checked
 * against the live project by the manual test described in the README.
 */
class FirestoreRulesTest {

    private static final Path RULES = Path.of("..", "firestore.rules");

    private String rules() throws Exception {
        return Files.readString(RULES, StandardCharsets.UTF_8);
    }

    /** The rules without // comments, so documentation cannot hide or fake a rule. */
    private String code() throws Exception {
        return rules().replaceAll("(?m)//.*$", "");
    }

    @Test
    void thereIsExactlyOneMatchBlock_thatDeniesEverything() throws Exception {
        String code = code();
        Matcher match = Pattern.compile("(?m)match\\s+(\\S+)\\s*\\{\\s*$").matcher(code);
        List<String> matches = new java.util.ArrayList<>();
        while (match.find()) {
            matches.add(match.group(1));
        }
        assertThat(matches).containsExactly("/databases/{database}/documents", "/{document=**}");
        assertThat(code).containsPattern("allow\\s+read\\s*,\\s*write\\s*:\\s*if\\s+false\\s*;");
    }

    @Test
    void noRuleEverAllowsAnything() throws Exception {
        String code = code();
        for (String allow : Pattern.compile("allow[^;]*;").matcher(code).results().map(r -> r.group()).toList()) {
            assertThat(allow).matches("allow\\s+read\\s*,\\s*write\\s*:\\s*if\\s+false\\s*;");
        }
        assertThat(code).doesNotContain("request.auth").doesNotContain("if true").doesNotContainPattern("allow\\s+(get|list|create|update|delete)");
    }

    @Test
    void theRulesDocumentEveryCollectionTheBackendUses() throws Exception {
        String text = rules();
        for (String collection : List.of("users/{uid}", "emergencyContacts", "emergencyAlerts/{id}", "deliveries", "events",
                "notificationJobs", "adminDevices", "incidentReports", "adminAuditLogs")) {
            assertThat(text).as("documented: " + collection).contains(collection);
        }
    }
}
