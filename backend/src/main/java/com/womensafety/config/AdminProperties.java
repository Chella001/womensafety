package com.womensafety.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Administrators, bound from {@code app.admin.uids} (environment variable {@code ADMIN_UIDS}, comma separated).
 * This allow-list is the ONLY way to become an administrator: nothing a client sends can change it.
 * An empty list means nobody is an administrator.
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(@DefaultValue List<String> uids) {

    public Set<String> adminUidSet() {
        return uids.stream().map(String::strip).filter(uid -> !uid.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAdmin(String uid) {
        return uid != null && adminUidSet().contains(uid);
    }
}
