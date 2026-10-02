package com.neuringo.neuringobe.child.security;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class ChildAccessScope {
    private final ChildRepository children;
    private final ClassroomRepository classrooms;

    public ChildAccessScope(ChildRepository children, ClassroomRepository classrooms) {
        this.children = children;
        this.classrooms = classrooms;
    }

    public void requireOwnerOrInstructor(Authentication authentication, UUID childId) {
        if (authentication != null
                && authentication.getPrincipal() instanceof ChildPrincipal principal) {
            if (principal.childId().equals(childId)
                    && children.findById(childId)
                            .map(child -> child.getStatus() == ChildStatus.ACTIVE)
                            .orElse(false)) {
                return;
            }
            throw hidden();
        }
        requireInstructor(authentication, childId);
    }

    public void requireInstructor(Authentication authentication, UUID childId) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)
                || authentication.getAuthorities().stream()
                        .noneMatch(a -> "ROLE_INSTRUCTOR".equals(a.getAuthority()))) {
            throw hidden();
        }
        Child child = children.findById(childId).orElseThrow(this::hidden);
        if (!classrooms.existsByClassIdAndInstructorId(child.getClassId(), user.userId())) {
            throw hidden();
        }
    }

    private ResourceNotFoundException hidden() {
        return new ResourceNotFoundException("CHILD_NOT_FOUND", "아동을 찾을 수 없습니다.");
    }
}
