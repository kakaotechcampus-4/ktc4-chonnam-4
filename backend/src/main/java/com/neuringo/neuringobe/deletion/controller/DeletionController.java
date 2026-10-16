package com.neuringo.neuringobe.deletion.controller;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.deletion.dto.WithdrawalRequest;
import com.neuringo.neuringobe.deletion.service.DeletionService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 아동·학급 영구 삭제(ADR 2026-10-04). 정본 05 는 삭제 대신 상태 변경(PATCH)을 두지만, S2 테스트 데이터 정리를 위해 03 VS-002 의 연쇄 영구
 * 삭제를 따른다. 경로는 정본 05 의 자원 경로를 그대로 쓴다. 강사 탈퇴도 같은 삭제 경로를 쓰므로 여기에 둔다.
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

    /**
     * 강사 즉시 탈퇴. 현재 비밀번호를 다시 확인한 뒤 계정과 소유 데이터를 모두 지운다. 토큰이 새도 비밀번호 없이는 탈퇴할 수 없다. DELETE 본문은 중간 프록시가
     * 버려도 표준 위반이 아니라, 비밀번호를 확실히 받으려고 POST 로 받는다.
     */
    @PostMapping("/api/v1/users/me/withdrawal")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody WithdrawalRequest request) {
        deletionService.deleteInstructor(user.userId(), request.password());
    }

    @DeleteMapping("/api/v1/classrooms/{classId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteClassroom(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID classId) {
        deletionService.deleteClassroom(user.userId(), classId);
    }
}
