package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import com.neuringo.neuringobe.scenario.dto.ScenarioConfirmationPreparation;
import com.neuringo.neuringobe.scenario.dto.ScenarioEdits;
import com.neuringo.neuringobe.scenario.repository.ScenarioConfirmationRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioDraftRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ScenarioConfirmationPreparationService {
    private final InitialScenarioPreparationService preparations;
    private final ScenarioConfirmationRepository confirmations;
    private final ScenarioCandidateQueryService candidates;
    private final ScenarioDraftRepository drafts;
    private final ScenarioGenerationService validator;
    private final ScenarioEditApplier editApplier;

    public ScenarioConfirmationPreparationService(
            InitialScenarioPreparationService preparations,
            ScenarioConfirmationRepository confirmations,
            ScenarioCandidateQueryService candidates,
            ScenarioDraftRepository drafts,
            ScenarioGenerationService validator,
            ScenarioEditApplier editApplier) {
        this.preparations = preparations;
        this.confirmations = confirmations;
        this.candidates = candidates;
        this.drafts = drafts;
        this.validator = validator;
        this.editApplier = editApplier;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ScenarioConfirmationPreparation prepare(
            UUID activityId,
            UUID reviewId,
            long revision,
            UUID scenarioId,
            int version,
            Authentication authentication) {
        return prepare(
                activityId,
                reviewId,
                revision,
                scenarioId,
                version,
                ScenarioEdits.none(),
                authentication);
    }

    public ScenarioConfirmationPreparation prepare(
            UUID activityId,
            UUID reviewId,
            long revision,
            UUID scenarioId,
            int version,
            ScenarioEdits requestedEdits,
            Authentication authentication) {
        var edits = requestedEdits.normalized();
        var input = preparations.prepare(activityId, authentication);
        UUID instructor = ((AuthenticatedUser) authentication.getPrincipal()).userId();
        String source =
                confirmations
                        .currentSource(
                                activityId, reviewId, revision, scenarioId, version, instructor)
                        .orElseThrow(
                                () -> conflict("SCENARIO_REVIEW_CHANGED", "승인할 현재 후보와 일치하지 않습니다."));
        if (!confirmations.openingReady(scenarioId))
            throw conflict("SCENARIO_OPENING_FOCUS_NOT_READY", "시작 질문과 작은 목표의 연결이 준비되지 않았습니다.");
        ScenarioApprovalConditions conditions = null;
        GeneratedScenario edited = null;
        if (!edits.isEmpty()) {
            GeneratedScenario original;
            try {
                original = drafts.read(scenarioId);
            } catch (tools.jackson.core.JacksonException invalidSource) {
                throw conflict("SCENARIO_CANDIDATE_INVALID", "수정할 원본 콘텐츠를 확인할 수 없습니다.");
            }
            var proposed = editApplier.apply(scenarioId, original, edits);
            if (!proposed.equals(original)) {
                try {
                    validator.validateStored(input, proposed);
                } catch (ApiException invalidEdit) {
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            "INVALID_SCENARIO_EDITS",
                            "수정한 시나리오가 콘텐츠 규칙을 충족하지 못합니다.");
                }
                edited = proposed;
                conditions = confirmedConditions(input, proposed);
            }
        }
        if (edited == null) {
            if (source.equals("REUSE")) {
                if (candidates
                        .selectForCandidate(input, authentication, scenarioId)
                        .candidates()
                        .isEmpty())
                    throw conflict("SCENARIO_CANDIDATE_INVALID", "재사용 후보가 현재 조건을 충족하지 못합니다.");
            } else {
                var generated = drafts.read(scenarioId);
                validator.validateStored(input, generated);
                conditions = confirmedConditions(input, generated);
            }
        }
        return new ScenarioConfirmationPreparation(
                input, source, confirmations.contentBody(scenarioId), conditions, edits, edited);
    }

    private ScenarioApprovalConditions confirmedConditions(
            com.neuringo.neuringobe.scenario.dto.InitialScenarioPreparation input,
            GeneratedScenario content) {
        var goal = input.learningGoal();
        var roles =
                content.characters().stream()
                        .filter(c -> !"LEARNER".equals(c.id()))
                        .map(c -> c.role())
                        .toList();
        return new ScenarioApprovalConditions(
                goal.title(),
                content.scenarioLevel(),
                goal.category(),
                goal.situationType(),
                roles,
                goal.requiredElements().isEmpty() ? null : goal.requiredElements());
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
