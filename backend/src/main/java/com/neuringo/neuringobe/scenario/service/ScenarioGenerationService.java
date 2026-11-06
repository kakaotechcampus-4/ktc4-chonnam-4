package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.scenario.dto.ScenarioGenerationContext;
import com.neuringo.neuringobe.scenario.dto.ScenarioPreparationInput;
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

    /** 한 요청에서 출력 계약 위반으로 다시 만드는 최대 횟수(첫 시도 포함). */
    static final int MAX_ATTEMPTS = 3;

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
        var teacherInput =
                new ScenarioGenerationInput.TeacherInput(
                        goal.title(),
                        goal.category(),
                        goal.situationType(),
                        goal.characters(),
                        goal.requiredElements(),
                        goal.forbiddenExpressions());
        var difficulty =
                ScenarioGenerationInput.Difficulty.of(context.input().difficulty().scenarioLevel());
        var trace =
                new AiTraceContext(
                        requestId,
                        context.snapshot().activityId(),
                        context.input().reuseKey().classId(),
                        context.input().difficulty().childId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        UUID.randomUUID());
        // 출력 계약 위반(INVALID_OUTPUT_FORMAT)은 재시도 가능한 실패다(backend/docs/ai-provider-contract.md).
        // 실패 코드를 revision.failure_codes 로 붙여 같은 요청 안에서 다시 만든다. 제공자 실패는 다시 부르지 않는다.
        var failureCodes = new ArrayList<String>();
        long elapsedMs = 0;
        int inputTokens = 0;
        int outputTokens = 0;
        for (int attempt = 1; ; attempt++) {
            var input =
                    new ScenarioGenerationInput(
                            teacherInput, difficulty, revision(previous, failureCodes));
            var prompt =
                    prompts.generation(trace, new AiAttemptContext(0, attempt, 0), attempt, input);
            AiCallResult<LlmCompletion> result;
            try {
                result = provider.complete(prompt.request());
            } catch (RuntimeException providerFailure) {
                log.info(
                        "event=scenario_generation_failed activityId={} reviewId={} failure=UNEXPECTED_PROVIDER_ERROR attempts={}",
                        context.snapshot().activityId(),
                        context.snapshot().reviewId(),
                        attempt);
                throw generationFailed(HttpStatus.BAD_GATEWAY);
            }
            if (result instanceof AiCallResult.Failure<LlmCompletion> failure) {
                log.info(
                        "event=scenario_generation_failed activityId={} reviewId={} failure={} elapsedMs={} attempts={}",
                        context.snapshot().activityId(),
                        context.snapshot().reviewId(),
                        failure.failure().type(),
                        elapsedMs + failure.metadata().elapsedMs(),
                        attempt);
                HttpStatus status =
                        switch (failure.failure().type()) {
                            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                            case AUTHENTICATION_ERROR, PROVIDER_UNAVAILABLE, RATE_LIMITED ->
                                    HttpStatus.SERVICE_UNAVAILABLE;
                            default -> HttpStatus.BAD_GATEWAY;
                        };
                throw generationFailed(status);
            }
            var success = (AiCallResult.Success<LlmCompletion>) result;
            elapsedMs += success.metadata().elapsedMs();
            inputTokens += tokens(success.metadata().inputTokens());
            outputTokens += tokens(success.metadata().outputTokens());
            GeneratedScenario scenario;
            try {
                scenario = prompt.parser().parse(success.data().content());
            } catch (InvalidLlmOutputException invalid) {
                String code = failureCode(invalid);
                log.info(
                        "event=scenario_generation_invalid_output activityId={} reviewId={} attempt={} reason={}",
                        context.snapshot().activityId(),
                        context.snapshot().reviewId(),
                        attempt,
                        code);
                if (attempt >= MAX_ATTEMPTS) {
                    log.info(
                            "event=scenario_generation_failed activityId={} reviewId={} failure=INVALID_OUTPUT_FORMAT elapsedMs={} attempts={} inputTokens={} outputTokens={}",
                            context.snapshot().activityId(),
                            context.snapshot().reviewId(),
                            elapsedMs,
                            attempt,
                            inputTokens,
                            outputTokens);
                    throw generationFailed(HttpStatus.BAD_GATEWAY);
                }
                if (!failureCodes.contains(code)) failureCodes.add(code);
                continue;
            } catch (RuntimeException unexpected) {
                log.info(
                        "event=scenario_generation_failed activityId={} reviewId={} failure=UNEXPECTED_PROVIDER_ERROR attempts={}",
                        context.snapshot().activityId(),
                        context.snapshot().reviewId(),
                        attempt);
                throw generationFailed(HttpStatus.BAD_GATEWAY);
            }
            validateConditions(goal, scenario);
            log.info(
                    "event=scenario_generation_completed activityId={} reviewId={} reason={} elapsedMs={} attempts={} inputTokens={} outputTokens={}",
                    context.snapshot().activityId(),
                    context.snapshot().reviewId(),
                    context.snapshot().candidateCount() == 0
                            ? "NO_MATCHING_CANDIDATE"
                            : "CANDIDATES_EXHAUSTED",
                    elapsedMs,
                    attempt,
                    inputTokens,
                    outputTokens);
            return scenario;
        }
    }

    /** 강사가 넘긴 직전 제안과 이번 요청에서 쌓인 출력 실패 코드를 함께 싣는다. 둘 다 없으면 첫 생성이다. */
    private static ScenarioGenerationInput.Revision revision(
            GeneratedScenario previous, List<String> failureCodes) {
        if (previous == null && failureCodes.isEmpty()) return null;
        return new ScenarioGenerationInput.Revision(
                previous,
                previous == null ? List.of() : List.of("목표·조건·난이도를 유지하고 직전 제안과 다른 내용으로 구성해 주세요."),
                List.copyOf(failureCodes));
    }

    /** 파서 메시지 "CODE: field" 에서 코드만 꺼낸다(평가 도구 ScenarioEvalRunner 와 같은 규칙). */
    static String failureCode(InvalidLlmOutputException invalid) {
        String message = invalid.getMessage() == null ? "" : invalid.getMessage().strip();
        String code = message.split("[: ]", 2)[0];
        return code.isBlank() ? "INVALID_OUTPUT_FORMAT" : code;
    }

    private static int tokens(Integer value) {
        return value == null ? 0 : value;
    }

    private static ApiException generationFailed(HttpStatus status) {
        return new ApiException(
                status, "SCENARIO_GENERATION_FAILED", "시나리오를 생성하지 못했습니다. 다시 시도해 주세요.");
    }

    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public void validateStored(ScenarioPreparationInput input, GeneratedScenario scenario) {
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
                        ScenarioGenerationInput.Difficulty.of(input.difficulty().scenarioLevel()),
                        null);
        var trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        input.difficulty().activityId(),
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
