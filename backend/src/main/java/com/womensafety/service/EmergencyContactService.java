package com.womensafety.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.womensafety.config.NotificationProperties;
import com.womensafety.config.PolicyProperties;
import com.womensafety.dto.EmergencyContactRequest;
import com.womensafety.dto.UpdateEmergencyContactRequest;
import com.womensafety.dto.VerificationStartedResponse;
import com.womensafety.exception.ConflictException;
import com.womensafety.exception.InvalidRequestException;
import com.womensafety.exception.NotificationUnavailableException;
import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.exception.TooManyRequestsException;
import com.womensafety.model.EmergencyContact;
import com.womensafety.notification.SmsNotificationProvider;
import com.womensafety.repository.EmergencyContactRepository;
import com.womensafety.repository.UserRepository;
import com.womensafety.security.FirebaseUserPrincipal;
import com.womensafety.validation.PersonNames;
import com.womensafety.validation.PhoneNumbers;
import org.springframework.stereotype.Service;

/**
 * Emergency contacts of the authenticated user.
 *
 * <p>Adding a contact notifies nobody. A contact becomes eligible for SOS messages only after (1) the user confirms the
 * person agreed to receive them (consent) and (2) the contact proves it controls the number by entering a 6-digit code
 * that was texted to it (verification). The user's own login number says nothing about somebody else's number.</p>
 */
@Service
public class EmergencyContactService {

    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Duration SEND_WINDOW = Duration.ofHours(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmergencyContactRepository repository;
    private final UserRepository users;
    private final PhoneNumbers phones;
    private final SmsNotificationProvider sms;
    private final NotificationProperties notifications;
    private final PolicyProperties policy;
    private final Clock clock;

    public EmergencyContactService(EmergencyContactRepository repository, UserRepository users, PhoneNumbers phones,
                                   SmsNotificationProvider sms, NotificationProperties notifications,
                                   PolicyProperties policy, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.phones = phones;
        this.sms = sms;
        this.notifications = notifications;
        this.policy = policy;
        this.clock = clock;
    }

    public List<EmergencyContact> list(String uid) {
        return repository.findAll(uid);
    }

    public EmergencyContact add(FirebaseUserPrincipal caller, EmergencyContactRequest request) {
        String uid = caller.uid();
        String phone = normalisePhone(caller, request.phoneNumber());
        List<EmergencyContact> existing = repository.findAll(uid);
        int max = policy.maxEmergencyContacts();
        if (existing.size() >= max) {
            throw new ConflictException("CONTACT_LIMIT", "You can save up to " + max + " emergency contacts. Remove one to add another.");
        }
        rejectDuplicate(existing, phone, null);
        Instant now = now();
        EmergencyContact contact = new EmergencyContact(
                UUID.randomUUID().toString(), PersonNames.normalise(request.name()), phone, clean(request.relationship()), now, now)
                .withConsent(Boolean.TRUE.equals(request.notifyConsent()), now);
        return repository.save(uid, contact);
    }

    public EmergencyContact update(FirebaseUserPrincipal caller, String contactId, UpdateEmergencyContactRequest request) {
        String uid = caller.uid();
        EmergencyContact current = find(uid, contactId);
        String name = request.name() == null ? current.name() : PersonNames.normalise(request.name());
        String phone = request.phoneNumber() == null ? current.phoneNumber() : normalisePhone(caller, request.phoneNumber());
        String relationship = request.relationship() == null ? current.relationship() : clean(request.relationship());
        if (!phone.equals(current.phoneNumber())) {
            rejectDuplicate(repository.findAll(uid), phone, contactId);
        }
        Instant now = now();
        EmergencyContact updated = current.withDetails(name, phone, relationship, now);
        if (request.notifyConsent() != null && request.notifyConsent() != updated.notifyConsent()) {
            updated = updated.withConsent(request.notifyConsent(), now);
        }
        return repository.save(uid, updated);
    }

    public void delete(String uid, String contactId) {
        if (!VALID_ID.matcher(contactId).matches() || !repository.delete(uid, contactId)) {
            throw notFound();
        }
    }

    /* ---------- verification ---------- */

    /**
     * Texts a 6-digit code to the contact. In TEST mode nothing is sent and the code is returned so the flow can be tried;
     * in REAL mode the response never contains it. At most a few codes per contact per hour (SMS cost and abuse control).
     */
    public VerificationStartedResponse startVerification(String uid, String contactId) {
        EmergencyContact contact = find(uid, contactId);
        if (contact.verified()) {
            throw new ConflictException("ALREADY_VERIFIED", "This contact is already verified.");
        }
        if (!contact.notifyConsent()) {
            throw new InvalidRequestException("notifyConsent",
                    "Confirm that this person agreed to receive SafeHer emergency messages before sending a code.");
        }
        Instant now = now();
        EmergencyContact.Challenge previous = contact.challenge();
        boolean windowOpen = previous != null && previous.windowStart() != null && previous.windowStart().plus(SEND_WINDOW).isAfter(now);
        int sent = windowOpen ? previous.sentInWindow() : 0;
        Instant windowStart = windowOpen ? previous.windowStart() : now;
        if (sent >= notifications.verificationMaxSendsPerHour()) {
            long wait = Math.max(1, Duration.between(now, windowStart.plus(SEND_WINDOW)).getSeconds());
            throw new TooManyRequestsException("A code was sent several times already. Please wait before requesting another.", wait);
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant expires = now.plus(Duration.ofMinutes(notifications.verificationCodeMinutes()));
        // Stored first: even if the SMS fails, the attempt counts toward the hourly limit.
        repository.save(uid, contact.withChallenge(
                new EmergencyContact.Challenge(hash(contactId, code), expires, 0, sent + 1, windowStart), now));

        String ownerName = users.findByUid(uid).map(u -> u.fullName()).orElse(null);
        SmsNotificationProvider.SmsSendResult result;
        try {
            result = sms.send(new SmsNotificationProvider.SmsMessage(contact.phoneNumber(),
                    SosMessages.verification(ownerName, code, notifications.verificationCodeMinutes()), null));
        } catch (RuntimeException e) {
            result = SmsNotificationProvider.SmsSendResult.transientFailure("PROVIDER_EXCEPTION");
        }
        if (result.outcome() != SmsNotificationProvider.SmsSendResult.Outcome.ACCEPTED) {
            throw new NotificationUnavailableException("We couldn't send the verification message. Please try again later.");
        }
        boolean test = sms.simulated();
        return new VerificationStartedResponse(expires, test, test ? code : null, test
                ? "TEST MODE: no SMS was sent. Use the code shown here."
                : "A code was sent to " + PhoneNumbers.mask(contact.phoneNumber()) + ". Ask them to read it to you.");
    }

    public EmergencyContact confirmVerification(String uid, String contactId, String code) {
        EmergencyContact contact = find(uid, contactId);
        if (contact.verified()) {
            return contact;
        }
        Instant now = now();
        EmergencyContact.Challenge challenge = contact.challenge();
        if (challenge == null || challenge.codeHash() == null || challenge.expiresAt() == null || !challenge.expiresAt().isAfter(now)) {
            throw new InvalidRequestException("code", "That code has expired. Request a new one.");
        }
        if (challenge.failedAttempts() >= notifications.verificationMaxAttempts()) {
            throw new InvalidRequestException("code", "Too many wrong codes. Request a new code.");
        }
        boolean match = MessageDigest.isEqual(hash(contactId, code).getBytes(StandardCharsets.UTF_8),
                challenge.codeHash().getBytes(StandardCharsets.UTF_8));
        if (!match) {
            repository.save(uid, contact.withChallenge(new EmergencyContact.Challenge(challenge.codeHash(), challenge.expiresAt(),
                    challenge.failedAttempts() + 1, challenge.sentInWindow(), challenge.windowStart()), now));
            throw new InvalidRequestException("code", "That code isn't correct.");
        }
        return repository.save(uid, contact.markVerified(now));
    }

    /* ---------- helpers ---------- */

    private EmergencyContact find(String uid, String contactId) {
        if (!VALID_ID.matcher(contactId).matches()) {
            throw notFound();
        }
        return repository.findById(uid, contactId).orElseThrow(EmergencyContactService::notFound);
    }

    /** Contact numbers without a country code are read in the region of the user's own number. */
    private String normalisePhone(FirebaseUserPrincipal caller, String raw) {
        String e164 = phones.toE164(raw, phones.regionOf(caller.phoneNumber()), "phoneNumber");
        if (e164.equals(caller.phoneNumber())) {
            throw new InvalidRequestException("phoneNumber", "That is your own number. Add someone else.");
        }
        return e164;
    }

    private static void rejectDuplicate(List<EmergencyContact> existing, String phone, String ignoreId) {
        boolean duplicate = existing.stream()
                .anyMatch(c -> !c.id().equals(ignoreId) && c.phoneNumber().equals(phone));
        if (duplicate) {
            throw new ConflictException("DUPLICATE_CONTACT", "You already have an emergency contact with that number.");
        }
    }

    private static String hash(String contactId, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((contactId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("That emergency contact does not exist.");
    }
}
