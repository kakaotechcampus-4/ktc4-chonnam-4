package com.neuringo.neuringobe.roleplay.service;

import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.roleplay.application.RoleplayVoiceFailureProgress;
import com.neuringo.neuringobe.roleplay.application.RoleplayVoiceRecoverySource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JdbcRoleplayVoiceRecoverySource implements RoleplayVoiceRecoverySource {
    private final JdbcTemplate jdbc;

    public JdbcRoleplayVoiceRecoverySource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 검증 중 다른 입력이 늘린 실패도 반영하며 요청의 오래된 체크포인트로 덮어쓰지 않는다. */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean textRecommended(Key key, RoleplayTurnDeadline deadline) {
        return progress(key, deadline).textRecommended();
    }

    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RoleplayVoiceFailureProgress progress(Key key, RoleplayTurnDeadline deadline) {
        deadline.requireActive();
        try {
            long millis = Math.max(1, (deadline.remainingNanos() + 999999) / 1000000);
            jdbc.queryForObject(
                    "select set_config('statement_timeout', ?, true)", String.class, millis + "ms");
            var rows =
                    jdbc.queryForList(
                            """
                select coalesce(v.failure_count,0)
                from roleplay_session s
                join activity a on a.activity_id=s.activity_id and a.child_id=s.child_id
                join child c on c.child_id=s.child_id
                left join roleplay_voice_input_state v on v.session_id=s.session_id
                where s.session_id=? and s.child_id=? and s.activity_id=?
                  and s.scenario_id=? and s.scenario_version=? and a.scenario_id=s.scenario_id
                  and s.status='IN_PROGRESS' and a.status='IN_PROGRESS' and c.status='ACTIVE'
                """,
                            Integer.class,
                            key.sessionId(),
                            key.childId(),
                            key.activityId(),
                            key.scenarioId(),
                            key.scenarioVersion());
            deadline.requireActive();
            if (rows.size() != 1)
                throw new ResourceNotFoundException("ROLEPLAY_NOT_FOUND", "역할극을 찾을 수 없습니다.");
            return new RoleplayVoiceFailureProgress(rows.getFirst());
        } catch (QueryTimeoutException timeout) {
            deadline.cancel();
            deadline.requireActive();
            throw timeout;
        }
    }
}
