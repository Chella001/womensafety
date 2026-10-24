package com.womensafety.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.womensafety.config.NotificationProperties;
import com.womensafety.exception.ConflictException;
import com.womensafety.exception.InvalidRequestException;
import com.womensafety.exception.ResourceNotFoundException;
import com.womensafety.model.AdminDevice;
import com.womensafety.repository.AdminDeviceRepository;
import org.springframework.stereotype.Service;

/**
 * Push-notification devices of administrators. Only a caller who already passed the {@code ROLE_ADMIN} check can reach
 * this class; a device always belongs to the administrator who registered it, and nobody can list or remove another
 * administrator's devices. The FCM token is stored for sending only and is never returned.
 */
@Service
public class AdminDeviceService {

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_:.\\-]{20,4096}$");

    private final AdminDeviceRepository repository;
    private final NotificationProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public AdminDeviceService(AdminDeviceRepository repository, NotificationProperties properties, AuditService audit, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    public AdminDevice register(String adminUid, String token, String label) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new InvalidRequestException("token", "That is not a valid device token.");
        }
        String id = sha256(token);
        var existing = repository.findById(id);
        if (existing.isPresent() && !existing.get().uid().equals(adminUid)) {
            // The token already belongs to another administrator's session: refuse rather than move it.
            throw new ConflictException("DEVICE_IN_USE", "That device is registered to another account.");
        }
        List<AdminDevice> mine = repository.findByUid(adminUid);
        if (existing.isEmpty() && mine.size() >= properties.maxAdminDevices()) {
            throw new ConflictException("DEVICE_LIMIT", "You can register up to " + properties.maxAdminDevices() + " devices. Remove one first.");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AdminDevice device = new AdminDevice(id, adminUid, token, cleanLabel(label),
                existing.map(AdminDevice::createdAt).orElse(now), now);
        repository.save(device);
        if (existing.isEmpty()) {
            audit.recordAdminAction(adminUid, "REGISTER_ADMIN_DEVICE", adminUid, Map.of("deviceId", id.substring(0, 12)));
        }
        return device;
    }

    public List<AdminDevice> listOwn(String adminUid) {
        return repository.findByUid(adminUid);
    }

    public void remove(String adminUid, String deviceId) {
        AdminDevice device = repository.findById(deviceId).filter(d -> d.uid().equals(adminUid))
                .orElseThrow(() -> new ResourceNotFoundException("That device does not exist."));
        repository.delete(device.id());
        audit.recordAdminAction(adminUid, "REMOVE_ADMIN_DEVICE", adminUid, Map.of("deviceId", deviceId.substring(0, Math.min(12, deviceId.length()))));
    }

    private static String cleanLabel(String label) {
        String clean = label == null ? "" : label.replaceAll("[\\p{Cntrl}]", "").strip();
        if (clean.isEmpty()) {
            return "Admin device";
        }
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
