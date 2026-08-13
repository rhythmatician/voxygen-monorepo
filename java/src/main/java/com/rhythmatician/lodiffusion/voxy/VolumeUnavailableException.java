package com.rhythmatician.lodiffusion.voxy;

/**
 * Thrown when the underlying voxel storage backend is unavailable.
 *
 * <p>Unchecked - extends {@code IllegalStateException} so callers are not
 * forced to declare it. Thrown instead of {@link WriteOutcome} when the
 * failure is not a normal runtime decision (SKIPPED_*) but a missing
 * binding or unavailable engine.
 */
public final class VolumeUnavailableException extends IllegalStateException {
    public VolumeUnavailableException(String message) {
        super(message);
    }

    public VolumeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
