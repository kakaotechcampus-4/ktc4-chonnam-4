package com.neuringo.neuringobe.roleplay.model;

/** Proposed delivery states, separate from activity/session lifecycle. */
public enum RoleplayDeliveryStatus {
    DELIVERED,
    REINPUT_REQUIRED,
    RETRY_REQUIRED,
    STOPPED,
    NOTICE_UNAVAILABLE,
    CONFLICT,
    PROCESSING,
    BUSY
}
