package com.neuringo.neuringobe.roleplay.model;

/** Proposed UI routing; no automatic retry or polling interval is implied. */
public enum RoleplayDeliveryAction {
    NONE,
    REQUEST_NEW_INPUT,
    RETRY_TURN,
    STOP_DIALOGUE,
    WAIT,
    RESUBMIT_SAME_REQUEST
}
