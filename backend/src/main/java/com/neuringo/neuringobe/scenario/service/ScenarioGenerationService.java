package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.scenario.dto.InitialScenarioPreparation;
import com.neuringo.neuringobe.scenario.dto.ScenarioGenerationContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(propagation = Propagation.NEVER)
public class ScenarioGenerationService {
    private static final Logger log = LoggerFactory.getLogger(ScenarioGenerationService.class);
    private static final Pattern WHITESPACE =
            Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private final ObjectProvider<LlmProvider> providers;
    private final ScenarioPromptFactory prompts;
    private final JsonMapper json;

    public ScenarioGenerationService(ObjectProvider<LlmProvider> providers, JsonMapper json) {
        this.providers = providers;
        this.json = json;
        this.prompts = new ScenarioPromptFactory(json, null);
    }

    public GeneratedScenario generate(ScenarioGenerationContext context, UUID requestId) {
        var provider = providers.getIfAvailable();
        if (provider == null)
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SCENARIO_GENERATOR_UNAVAILABLE",
                    "시나리오 생성 연결이 준비되지 않았습니다.");
        var goal = context.input().learningGoal();
        var previous = context.previous();
        var input =
                new ScenarioGenerationInput(
                        new ScenarioGenerationInput.TeacherInput(
                                goal.title(),
                                goal.category(),
                                goal.situationType(),
                                goal.characters(),
                                goal.requiredElements(),
                                goal.forbiddenExpressions()),
                        ScenarioGenerationInput.Difficulty.of(
                                context.input().initialDifficulty().scenarioLevel()),
                        previous == null
                                ? null
                                : new ScenarioGenerationInput.Revision(
                                        previous,
                                        List.of("목표·조건·난이도를 유지하고 직전 제안과 다른 내용으로 구성해 주세요."),
                                        List.of()));
        var trace =
                new AiTraceContext(
                        requestId,
                        context.snapshot().activityId(),
                        context.input().reuseKey().classId(),
                        context.input().initialDifficulty().childId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID());
        var prompt = prompts.generation(trace, new AiAttemptContext(0, 1, 0), 1, input);
        AiCallResult<GeneratedScenario> result;
        try {
            result = new StructuredLlmExecutor(provider).execute(prompt.request(), prompt.parser());
        } catch (RuntimeException providerFailure) {
            log.info(
                    "event=scenario_generation_failed activityId={} reviewId={} failure=UNEXPECTED_PROVIDER_ERROR",
                    context.snapshot().activityId(),
                    context.snapshot().reviewId());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "SCENARIO_GENERATION_FAILED",
                    "시나리오를 생성하지 못했습니다. 다시 시도해 주세요.");
        }
        if (result instanceof AiCallResult.Failure<GeneratedScenario> failure) {
            log.info(
                    "event=scenario_generation_failed activityId={} reviewId={} failure={} elapsedMs={}",
                    context.snapshot().activityId(),
                    context.snapshot().reviewId(),
                    failure.failure().type(),
                    failure.metadata().elapsedMs());
            HttpStatus status =
                    switch (failure.failure().type()) {
                        case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                        case AUTHENTICATION_ERROR, PROVIDER_UNAVAILABLE, RATE_LIMITED ->
                                HttpStatus.SERVICE_UNAVAILABLE;
                        default -> HttpStatus.BAD_GATEWAY;
                    };
            throw new ApiException(
                    status, "SCENARIO_GENERATION_FAILED", "시나리오를 생성하지 못했습니다. 다시 시도해 주세요.");
        }
        var success = (AiCallResult.Success<GeneratedScenario>) result;
        var scenario = success.data();
        validateConditions(goal, scenario);
        log.info(
                "event=scenario_generation_completed activityId={} reviewId={} reason={} elapsedMs={} inputTokens={} outputTokens={}",
                context.snapshot().activityId(),
                context.snapshot().reviewId(),
                context.snapshot().candidateCount() == 0
                        ? "NO_MATCHING_CANDIDATE"
                        : "CANDIDATES_EXHAUSTED",
                success.metadata().elapsedMs(),
                success.metadata().inputTokens(),
                success.metadata().outputTokens());
        return scenario;
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public void validateStored(InitialScenarioPreparation input, GeneratedScenario scenario) {
        var goal = input.learningGoal();
        var conditions =
                new ScenarioGenerationInput(
                        new ScenarioGenerationInput.TeacherInput(
                                goal.title(),
                                goal.category(),
                                goal.situationType(),
                                goal.characters(),
                                goal.requiredElements(),
                                goal.forbiddenExpressions()),
                        ScenarioGenerationInput.Difficulty.of(
                                input.initialDifficulty().scenarioLevel()),
                        null);
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        input.initialDifficulty().activityId(),
                        input.reuseKey().classId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        try {
            prompts.generation(trace, new AiAttemptContext(0, 1, 0), 1, conditions)
                    .parser()
                    .parse(json.writeValueAsString(scenario));
            validateConditions(goal, scenario);
        } catch (RuntimeException invalidContent) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "SCENARIO_CANDIDATE_INVALID",
                    "후보가 현재 조건이나 콘텐츠 규칙을 충족하지 못합니다.");
        }
    }

    private void validateConditions(LearningGoalResponse goal, GeneratedScenario scenario) {
        var expectedRoles =
                goal.characters().stream()
                        .map(c -> normalize((String) c.get("role")))
                        .sorted()
                        .toList();
        var actualRoles =
                scenario.characters().stream()
                        .filter(c -> !"LEARNER".equals(c.id()))
                        .map(c -> normalize(c.role()))
                        .sorted()
                        .toList();
        if (!expectedRoles.isEmpty() && !expectedRoles.equals(actualRoles))
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "SCENARIO_GENERATION_INVALID_OUTPUT",
                    "요청한 등장인물 구성을 충족하지 못했습니다.");
        var texts = new ArrayList<>(List.of(scenario.title(), scenario.summary()));
        scenario.characters().forEach(c -> texts.add(c.role()));
        texts.addAll(scenario.scenarioFacts());
        texts.addAll(scenario.startingQuestions().values());
        scenario.microGoals()
                .forEach(
                        g -> {
                            texts.add(g.description());
                            texts.addAll(g.requiredEvidence());
                            texts.add(g.achievementExample());
                        });
        if (texts.stream()
                .map(ScenarioGenerationService::compact)
                .anyMatch(
                        text ->
                                goal.forbiddenExpressions().stream()
                                        .map(ScenarioGenerationService::compact)
                                        .anyMatch(text::contains)))
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "SCENARIO_GENERATION_INVALID_OUTPUT",
                    "금지 표현이 포함된 시나리오입니다.");
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    private static String compact(String text) {
        return WHITESPACE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
