package com.womensafety.service;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import com.womensafety.exception.IdentityServiceException;
import org.springframework.stereotype.Service;

/** Administrative operations on Firebase Authentication accounts (kept behind one class so tests can mock it). */
@Service
public class IdentityAdminService {

    private final FirebaseAuth firebaseAuth;

    public IdentityAdminService(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

    /** A disabled Firebase user can no longer sign in or refresh tokens. */
    public void setDisabled(String uid, boolean disabled) {
        try {
            firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(disabled));
        } catch (FirebaseAuthException e) {
            throw new IdentityServiceException("Could not update the Firebase account", e);
        }
    }

    /** Invalidates every refresh token of the user, so all of their devices must sign in again. */
    public void revokeSessions(String uid) {
        try {
            firebaseAuth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException e) {
            throw new IdentityServiceException("Could not revoke the Firebase sessions", e);
        }
    }
}
