package com.womensafety.repository;

import java.util.List;
import java.util.Optional;

import com.womensafety.model.AdminDevice;

/** Administrator push devices ({@code adminDevices}). The FCM token never leaves the backend. */
public interface AdminDeviceRepository {

    void save(AdminDevice device);

    Optional<AdminDevice> findById(String id);

    List<AdminDevice> findAll();

    List<AdminDevice> findByUid(String uid);

    /** @return true when a device was removed */
    boolean delete(String id);
}
