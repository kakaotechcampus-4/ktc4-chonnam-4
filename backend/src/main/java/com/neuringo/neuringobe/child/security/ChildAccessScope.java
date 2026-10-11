package com.neuringo.neuringobe.child.security;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.util.UUID;
import java.util.function.Supplier;
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
        requireOwnerOrInstructor(authentication, childId, this::hidden);
    }

    /**
     * 아동 ID가 아닌 다른 자원의 ID(활동, 목표 등)로 들어온 요청용이다. 권한이 없을 때 그 자원이 없는 것과 같은 오류를 던져야 남의 자원이 있다는 게 드러나지
     * 않는다.
     */
    public void requireOwnerOrInstructor(
            Authentication authentication,
            UUID childId,
            Supplier<ResourceNotFoundException> hidden) {
        if (authentication != null
                && authentication.getPrincipal() instanceof ChildPrincipal principal) {
            if (principal.childId().equals(childId)
                    && children.findById(childId)
                            .map(child -> child.getStatus() == ChildStatus.ACTIVE)
                            .orElse(false)) {
                return;
            }
            throw hidden.get();
        }
        requireInstructor(authentication, childId, hidden);
    }

    public void requireInstructor(Authentication authentication, UUID childId) {
        requireInstructor(authentication, childId, this::hidden);
    }

    /**
     * 다른 자원의 ID로 들어온 요청용이다. {@link #requireOwnerOrInstructor(Authentication, UUID, Supplier)} 참고.
     */
    public void requireInstructor(
            Authentication authentication,
            UUID childId,
            Supplier<ResourceNotFoundException> hidden) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)
                || authentication.getAuthorities().stream()
                        .noneMatch(a -> "ROLE_INSTRUCTOR".equals(a.getAuthority()))) {
            throw hidden.get();
        }
        Child child = children.findById(childId).orElseThrow(hidden);
        if (!classrooms.existsByClassIdAndInstructorId(child.getClassId(), user.userId())) {
            throw hidden.get();
        }
    }

    private ResourceNotFoundException hidden() {
        return new ResourceNotFoundException("CHILD_NOT_FOUND", "아동을 찾을 수 없습니다.");
    }
}
