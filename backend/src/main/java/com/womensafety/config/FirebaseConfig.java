package com.womensafety.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.cloud.FirestoreClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

/**
 * Initialises the Firebase Admin SDK. Credentials come from {@code FIREBASE_CREDENTIALS_PATH}
 * or, when that is unset, from Application Default Credentials ({@code GOOGLE_APPLICATION_CREDENTIALS}).
 * The application refuses to start without valid credentials instead of pretending to authenticate.
 * Tests use the {@code test} profile and supply mocks instead.
 */
@Configuration
@Profile("!test")
public class FirebaseConfig {

    private static final String DEFAULT_DATABASE_ID = "(default)";

    @Bean
    public FirebaseApp firebaseApp(FirebaseProperties properties) {
        FirebaseOptions.Builder options = FirebaseOptions.builder().setCredentials(loadCredentials(properties));
        if (StringUtils.hasText(properties.projectId())) {
            options.setProjectId(properties.projectId());
        }
        return FirebaseApp.getApps().isEmpty()
                ? FirebaseApp.initializeApp(options.build())
                : FirebaseApp.getInstance();
    }

    @Bean
    public FirebaseAuth firebaseAuth(FirebaseApp app) {
        return FirebaseAuth.getInstance(app);
    }

    @Bean
    public FirebaseMessaging firebaseMessaging(FirebaseApp app) {
        return FirebaseMessaging.getInstance(app);
    }

    @Bean
    public Firestore firestore(FirebaseApp app, FirebaseProperties properties) {
        String databaseId = properties.firestoreDatabaseId();
        if (!StringUtils.hasText(databaseId) || DEFAULT_DATABASE_ID.equals(databaseId)) {
            return FirestoreClient.getFirestore(app);
        }
        return FirestoreClient.getFirestore(app, databaseId);
    }

    private GoogleCredentials loadCredentials(FirebaseProperties properties) {
        try {
            if (StringUtils.hasText(properties.credentialsPath())) {
                Path path = Path.of(properties.credentialsPath());
                if (!Files.isReadable(path)) {
                    throw new IllegalStateException(
                            "FIREBASE_CREDENTIALS_PATH points to a file that cannot be read: " + path);
                }
                try (InputStream in = new FileInputStream(path.toFile())) {
                    return GoogleCredentials.fromStream(in);
                }
            }
            return GoogleCredentials.getApplicationDefault();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Firebase credentials not found. Set FIREBASE_CREDENTIALS_PATH to your service-account JSON "
                            + "(or GOOGLE_APPLICATION_CREDENTIALS) and FIREBASE_PROJECT_ID. See the README.", e);
        }
    }
}
