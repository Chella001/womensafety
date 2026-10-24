package com.womensafety.model;

/**
 * Lifecycle of an SOS alert RECORD.
 *
 * <ul>
 *   <li>ACTIVE — newly created by the user, not yet seen by an administrator ("new" in the admin console)</li>
 *   <li>ACKNOWLEDGED — an administrator has seen the record; this does NOT mean help was dispatched</li>
 *   <li>RESOLVED — an administrator closed it with a resolution note</li>
 *   <li>CANCELLED — the user cancelled it before an administrator acknowledged it</li>
 * </ul>
 */
public enum AlertStatus {
    ACTIVE, ACKNOWLEDGED, RESOLVED, CANCELLED
}
