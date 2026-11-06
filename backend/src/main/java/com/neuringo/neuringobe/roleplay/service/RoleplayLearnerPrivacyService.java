package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.child.repository.ChildRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoleplayLearnerPrivacyService {
    private final ChildRepository children;

    public RoleplayLearnerPrivacyService(ChildRepository children) {
        this.children = children;
    }

    @Transactional(readOnly = true)
    public String learnerName(UUID childId) {
        return children.findById(childId)
                .orElseThrow(() -> new IllegalStateException("입력 처리 대상 아동을 확인할 수 없습니다."))
                .getDisplayName();
    }
}
