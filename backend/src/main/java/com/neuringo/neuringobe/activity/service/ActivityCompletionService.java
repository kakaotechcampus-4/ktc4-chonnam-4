package com.neuringo.neuringobe.activity.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.learningrecord.domain.LearningRecord;
import com.neuringo.neuringobe.learningrecord.repository.LearningRecordRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 활동 완료를 한곳에서 확정한다. 완료 상태·스탬프 지급 시각·학습 기록을 한 트랜잭션에서 함께 남긴다.
 *
 * <p>완료는 서버가 정한다. 화면은 완료를 요청하지 않고 {@code GET /activities/{id}/status} 로 결과를 읽는다. 퀴즈는 마지막 문항이 확정될 때
 * 이 서비스를 부른다. 역할극은 서버가 활동 목표 달성을 확인한 뒤 역할극용 메서드를 부른다.
 *
 * <p>호출하는 쪽이 활동 행을 잠근 트랜잭션 안에서 불러야 한다. 그래야 동시 요청에도 스탬프와 기록이 한 번만 생긴다. 기록의 활동 ID 유일 제약이 마지막 장치다.
 */
@Service
public class ActivityCompletionService {
    private final LearningRecordRepository records;
    private final Clock clock;

    public ActivityCompletionService(LearningRecordRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    /** 퀴즈를 다 푼 활동을 완료한다. 퀴즈만으로 끝나지 않는 유형(최초 결합)은 그대로 둔다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeAfterQuiz(Activity lockedActivity) {
        if (!lockedActivity.getActivityType().completesWithQuiz()) {
            return;
        }
        complete(lockedActivity);
    }

    /** 활동 행을 잠그고 역할극의 활동 목표 달성을 확인한 뒤 호출한다. 최초 결합의 퀴즈 완료 확인은 호출자가 맡는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeAfterRoleplay(Activity lockedActivity) {
        var type = lockedActivity.getActivityType();
        if (type != ActivityType.FIRST_COMBINED && type != ActivityType.ROLEPLAY) {
            throw new IllegalArgumentException("역할극으로 완료할 수 없는 활동 유형입니다.");
        }
        complete(lockedActivity);
    }

    private void complete(Activity lockedActivity) {
        Instant now = clock.instant();
        if (lockedActivity.complete(now)) {
            records.saveAndFlush(
                    new LearningRecord(UUID.randomUUID(), lockedActivity.getActivityId(), now));
        }
    }
}
