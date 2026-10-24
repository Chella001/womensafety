package com.womensafety.security;

/**
 * The identity of a caller whose Firebase ID token has been verified by the Admin SDK.
 *
 * @param uid         Firebase user id (also the Firestore document id of the user's profile)
 * @param phoneNumber verified phone number in E.164 form, taken from the token's {@code phone_number} claim
 * @param admin       true only when the uid is in the server-side administrator allow-list
 */
public record FirebaseUserPrincipal(String uid, String phoneNumber, boolean admin) {
}
