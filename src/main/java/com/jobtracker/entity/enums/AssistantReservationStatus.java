package com.jobtracker.entity.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of one Ask ApplyWell request slot.
 * <ul>
 *   <li>IN_PROGRESS: authorized, generation running (counts as in flight while the lease is valid).</li>
 *   <li>COMPLETED / CANCELLED / FAILED: the provider was reached and produced or may have produced output; charged.</li>
 *   <li>REFUNDED: the provider failed before emitting any output; not charged to the user's daily quota,
 *       but still counts toward rate windows and global limits because the provider was called.</li>
 *   <li>RELEASED: the request failed before the provider was called; counts nowhere.</li>
 * </ul>
 */
public enum AssistantReservationStatus {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    FAILED,
    REFUNDED,
    RELEASED;

    public static final Set<AssistantReservationStatus> CHARGED =
            EnumSet.of(IN_PROGRESS, COMPLETED, CANCELLED, FAILED);
    public static final Set<AssistantReservationStatus> PROVIDER_REACHED =
            EnumSet.of(IN_PROGRESS, COMPLETED, CANCELLED, FAILED, REFUNDED);
}
