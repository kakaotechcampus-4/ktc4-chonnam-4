package com.neuringo.neuringobe.child.dto;

import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import java.util.UUID;

public record ChildResponse(UUID childId, UUID classId, String displayName, ChildStatus status) {

    public static ChildResponse from(Child child) {
        return new ChildResponse(
                child.getChildId(), child.getClassId(), child.getDisplayName(), child.getStatus());
    }
}
