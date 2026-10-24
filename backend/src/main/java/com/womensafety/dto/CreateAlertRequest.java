package com.womensafety.dto;

import com.womensafety.model.LocationStatus;
import jakarta.validation.Valid;

/**
 * Body of {@code POST /api/sos}. Both fields are optional: an alert can be created with no location at all.
 *
 * @param location       the position the user chose to attach (validated server-side)
 * @param locationStatus why there is no location (denied, unavailable, timeout, not requested); ignored when a location
 *                       is present and never allowed to claim AVAILABLE without coordinates
 */
public record CreateAlertRequest(@Valid LocationRequest location, LocationStatus locationStatus) {
}
