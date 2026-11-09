package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.auth.security.AuthenticatedUser;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.scenario.domain.ScenarioApprovalConditions;
import com.neuringo.neuringobe.scenario.dto.ApprovedScenarioVersion;
import com.neuringo.neuringobe.scenario.dto.ScenarioCandidateCriteria;
import com.neuringo.neuringobe.scenario.dto.ScenarioCandidateSelection;
import com.neuringo.neuringobe.scenario.dto.ScenarioPreparationInput;
import com.neuringo.neuringobe.scenario.repository.ScenarioCandidateContentRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioCandidateRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ScenarioCandidateQueryService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioCandidateQueryService.class);
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final ScenarioReviewInputService preparations;
    private final ScenarioCandidateRepository candidates;
    private final ScenarioCandidateContentRepository contents;

    public ScenarioCandidateQueryService(
            ScenarioReviewInputService preparations,
            ScenarioCandidateRepository candidates,
            ScenarioCandidateContentRepository contents) {
        this.preparations = preparations;
        this.candidates = candidates;
        this.contents = contents;
    }

    public ScenarioCandidateSelection select(UUID activityId, Authentication authentication) {
        long started = System.nanoTime();
        return select(
                preparations.prepare(activityId, authentication), authentication, started, null);
    }

    ScenarioCandidateSelection selectPrepared(
            ScenarioPreparationInput input, Authentication authentication) {
        return select(input, authentication, System.nanoTime(), null);
    }

    ScenarioCandidateSelection selectForCandidate(
            ScenarioPreparationInput input, Authentication authentication, UUID candidate) {
        return select(input, authentication, System.nanoTime(), candidate);
    }

    private ScenarioCandidateSelection select(
            ScenarioPreparationInput input,
            Authentication authentication,
            long started,
            UUID candidateId) {
        UUID activityId = input.difficulty().activityId();
        var goal = input.learningGoal();
        ScenarioCandidateCriteria criteria;
        try {
            var roles =
                    goal.characters().stream()
                            .map(
                                    character -> {
                                        Object role = character.get("role");
                                        if (!(role instanceof String text) || text.isBlank())
                                            throw new IllegalArgumentException();
                                        return (String) role;
                                    })
                            .toList();
            criteria =
                    new ScenarioCandidateCriteria(
                            ((AuthenticatedUser) authentication.getPrincipal()).userId(),
                            input.difficulty().childId(),
                            new ScenarioApprovalConditions(
                                    goal.title(),
                                    input.difficulty().scenarioLevel(),
                                    goal.category(),
                                    goal.situationType(),
                                    roles,
                                    goal.requiredElements()),
                            goal.forbiddenExpressions());
        } catch (IllegalArgumentException invalidConditions) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_GOAL_CONDITIONS",
                    "학습 목표 조건을 확인할 수 없습니다.");
        }
        long queryStarted = System.nanoTime();
        var selected = candidates.select(criteria, candidateId);
        long queryFinished = System.nanoTime();
        var result = filterForbidden(selected, criteria.forbiddenExpressions());
        long finished = System.nanoTime();
        log.info(
                "event=scenario_reuse_selection ruleVersion=2 purpose={} activityId={} pool={} eligible={} exclusions={} queryMs={} contentCheckMs={} totalMs={}",
                candidateId == null ? "PREPARE" : "CONFIRM",
                activityId,
                result.poolScenarioCount(),
                result.candidates().size(),
                result.exclusionCounts(),
                (queryFinished - queryStarted) / 1_000_000.0,
                (finished - queryFinished) / 1_000_000.0,
                (finished - started) / 1_000_000.0);
        return result;
    }

    private ScenarioCandidateSelection filterForbidden(
            ScenarioCandidateSelection selected, List<String> forbidden) {
        if (forbidden.isEmpty()) return selected;
        var terms = forbidden.stream().map(ScenarioCandidateQueryService::compact).toList();
        if (terms.stream().anyMatch(String::isEmpty)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "INVALID_GOAL_CONDITIONS",
                    "금지 표현을 확인할 수 없습니다.");
        }
        if (selected.candidates().isEmpty()) return selected;
        var fields =
                contents.readDisplayFields(
                        selected.candidates().stream()
                                .map(ApprovedScenarioVersion::scenarioId)
                                .toList());
        var eligible = new ArrayList<ApprovedScenarioVersion>();
        var exclusions = new HashMap<>(selected.exclusionCounts());
        for (var candidate : selected.candidates()) {
            var texts = fields.get(candidate.scenarioId());
            String reason = null;
            if (texts == null) reason = "CONTENT_UNVERIFIED";
            else if (texts.stream()
                    .map(ScenarioCandidateQueryService::compact)
                    .anyMatch(text -> terms.stream().anyMatch(text::contains)))
                reason = "FORBIDDEN_EXPRESSION";
            if (reason == null) eligible.add(candidate);
            else exclusions.merge(reason, 1L, Long::sum);
        }
        return new ScenarioCandidateSelection(eligible, selected.poolScenarioCount(), exclusions);
    }

    private static String compact(String value) {
        return WHITESPACE.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
