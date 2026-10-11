package com.neuringo.neuringobe.deletion.controller;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.deletion.service.DeletionService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아동·학급 영구 삭제(ADR 2026-10-04). 정본 05 는 삭제 대신 상태 변경(PATCH)을 두지만, S2 테스트 데이터 정리를 위해 03 VS-002 의 연쇄 영구
 * 삭제를 따른다. 경로는 정본 05 의 자원 경로를 그대로 쓴다.
 */
@RestController
public class DeletionController {

    private final DeletionService deletionService;

    public DeletionController(DeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @DeleteMapping("/api/v1/children/{childId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChild(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID childId) {
        deletionService.deleteChild(user.userId(), childId);
    }

    @DeleteMapping("/api/v1/classrooms/{classId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClassroom(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID classId) {
        deletionService.deleteClassroom(user.userId(), classId);
    }
}
