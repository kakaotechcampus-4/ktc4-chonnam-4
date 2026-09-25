package com.neuringo.neuringobe.child.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "child")
public class Child {

    @Id
    @Column(name = "child_id")
    private UUID childId;

    @Column(name = "class_id", nullable = false)
    private UUID classId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ChildStatus status;

    protected Child() {}

    public Child(UUID childId, UUID classId, String displayName, ChildStatus status) {
        this.childId = childId;
        this.classId = classId;
        this.displayName = displayName;
        this.status = status;
    }

    public UUID getChildId() {
        return childId;
    }

    public UUID getClassId() {
        return classId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ChildStatus getStatus() {
        return status;
    }
}
