package com.adesa.attendance.attendance.network;

import com.adesa.attendance.common.ForbiddenException;

/** Requests an automatic location check; no attendance or device proof has been consumed. */
public class LocationRequiredException extends ForbiddenException {
    public LocationRequiredException() {
        super("Please allow location access to confirm that you are at the office.");
    }
}
