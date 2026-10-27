package com.neuringo.neuringobe.ai.eval;

import com.neuringo.neuringobe.ai.application.model.AiAttemptContext;
import com.neuringo.neuringobe.ai.application.model.AiCallResult;
import com.neuringo.neuringobe.ai.application.model.AiTraceContext;
import com.neuringo.neuringobe.ai.application.model.LlmCompletion;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.prompt.PreparedPrompt;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayPromptFactory;
import com.neuringo.neuringobe.ai.application.prompt.RoleplayTurnInput;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioGenerationInput;
import com.neuringo.neuringobe.ai.application.prompt.ScenarioPromptFactory;
import com.neuringo.neuringobe.ai.application.roleplay.RetryingRoleplayTurnExecutor;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnResult;
import com.neuringo.neuringobe.ai.application.roleplay.StructuredRoleplayTurnSteps;
import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.StructuredLlmExecutor;
import com.neuringo.neuringobe.ai.application.structured.output.AnalysisResult;
import com.neuringo.neuringobe.ai.application.structured.output.CandidateResponse;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 시나리오 하나를 만들고, 사람이 아동 역할로 한 턴씩 입력하며 역할극을 이어 가는 대화 세션.
 *
 * <p>시나리오 생성과 한 턴 처리(원인 분석 → 응답 생성 → 응답 평가, 재시도 포함)는 운영과 같은 프롬프트·검사·실행기를 쓴다. 턴 사이의 상태 갱신(목표 상태·지원
 * 단계·턴 수)은 운영 오케스트레이터가 아직 없어서 이 도구가 단순한 규칙으로 대신한다. 그래서 목표 전환·지원 단계 변화는 운영 동작이 아니라 모델의 판단을 따라가 보는
 * 용도다. 입력 처리(개인정보 가림·안전 처리)와 TTS 는 거치지 않는다.
 */
final class RoleplayChat {

    static final int MAX_SCENARIO_ATTEMPTS = 3;

    private static final UUID CLASS_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c5");
    private static final UUID CHILD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

    private final RecordingLlmProvider recorder;
    private final StructuredLlmExecutor llm;
    private final ScenarioPromptFactory scenarioPrompts;
    private final RoleplayPromptFactory roleplayPrompts;
    private final Duration turnBudget;
    private final Policy policy;
    private final UUID activityId = UUID.randomUUID();
    private final UUID scenarioId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private GeneratedScenario scenario;
    private String learningGoal;
    private Map<String, UUID> goalIds = Map.of();
    private final Map<UUID, String> goalStatuses = new LinkedHashMap<>();
    private final List<RoleplayTurnInput.DialogueLine> dialogue = new ArrayList<>();
    private UUID currentGoal;
    private String supportLevel;
    private int supportTurn;
    private int promptCount;
    private boolean answerRevealed;
    private int turnCount;
    private boolean finished;

    /** 턴 제한. 운영 정책(DEC-017)이 정해지기 전이라 실행 옵션으로 받는다. */
    record Policy(int maximumTurnCount, int maximumSupportTurnsPerMicroGoal) {}

    RoleplayChat(
            LlmProvider provider,
            ScenarioPromptFactory scenarioPrompts,
            RoleplayPromptFactory roleplayPrompts,
            Duration turnBudget,
            Policy policy) {
        this.recorder = new RecordingLlmProvider(provider);
        this.llm = new StructuredLlmExecutor(recorder);
        this.scenarioPrompts = Objects.requireNonNull(scenarioPrompts);
        this.roleplayPrompts = Objects.requireNonNull(roleplayPrompts);
        this.turnBudget = Objects.requireNonNull(turnBudget);
        this.policy = Objects.requireNonNull(policy);
    }

    /** 계약 검사를 통과할 때까지 최대 3번 만든다. 실패하면 실패 코드를 붙여 다시 만든다. */
    ScenarioResult generateScenario(ScenarioGenerationInput.TeacherInput teacher, String level) {
        AiTraceContext trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        activityId,
                        CLASS_ID,
                        null,
                        scenarioId,
                        1,
                        null,
                        null,
                        null,
                        null);
        List<String> failures = new ArrayList<>();
        int from = recorder.size();
        long startedAt = System.nanoTime();
        for (int attempt = 1; attempt <= MAX_SCENARIO_ATTEMPTS; attempt++) {
            List<String> codes = failures.stream().distinct().toList();
            ScenarioGenerationInput input =
                    new ScenarioGenerationInput(
                            teacher,
                            ScenarioGenerationInput.Difficulty.of(level),
                            codes.isEmpty()
                                    ? null
                                    : new ScenarioGenerationInput.Revision(null, List.of(), codes));
            PreparedPrompt<GeneratedScenario> prompt =
                    scenarioPrompts.generation(
                            trace, new AiAttemptContext(0, attempt, 0), attempt, input);
            AiCallResult<LlmCompletion> result = recorder.complete(prompt.request());
            if (result instanceof AiCallResult.Failure<LlmCompletion> failure) {
                return new ScenarioResult(
                        null,
                        failure.failure().type().name(),
                        failures,
                        elapsed(startedAt),
                        calls(from));
            }
            String content = ((AiCallResult.Success<LlmCompletion>) result).data().content();
            try {
                GeneratedScenario generated = prompt.parser().parse(content);
                start(generated, teacher.learningGoal());
                return new ScenarioResult(
                        generated, null, failures, elapsed(startedAt), calls(from));
            } catch (InvalidLlmOutputException exception) {
                failures.add(failureCode(exception));
            }
        }
        return new ScenarioResult(null, null, failures, elapsed(startedAt), calls(from));
    }

    record ScenarioResult(
            GeneratedScenario scenario,
            String providerFailure,
            List<String> formatFailures,
            long totalMs,
            int calls) {}

    /** 아동의 초기 지원 수준을 정하고 첫 질문(시작 질문)을 돌려준다. */
    String open(String initialSupportLevel) {
        requireScenario();
        String level = initialSupportLevel.toUpperCase(Locale.ROOT);
        String question = scenario.startingQuestions().get(level);
        if (question == null) {
            throw new IllegalArgumentException(
                    "시작 질문은 " + scenario.startingQuestions().keySet() + " 중 하나로 고른다");
        }
        supportLevel = level;
        dialogue.add(new RoleplayTurnInput.DialogueLine(RoleplayTurnInput.Speaker.AI, question));
        return question;
    }

    /** 아동 발화 하나를 운영과 같은 경로로 처리하고, 전달된 응답이면 상태를 갱신한다. */
    TurnOutcome turn(String utterance) {
        requireScenario();
        if (supportLevel == null) throw new IllegalStateException("open() first");
        if (finished) throw new IllegalStateException("roleplay already finished");
        turnCount++;
        UUID turnId = UUID.randomUUID();
        RoleplayTurnInput input =
                new RoleplayTurnInput(
                        scenarioInput(),
                        new RoleplayTurnInput.State(
                                currentGoal,
                                supportLevel,
                                supportTurn,
                                promptCount,
                                answerRevealed,
                                turnCount,
                                policy.maximumTurnCount(),
                                policy.maximumSupportTurnsPerMicroGoal()),
                        new RoleplayTurnInput.LearnerTurn(turnId, utterance),
                        dialogue);
        AiTraceContext trace =
                new AiTraceContext(
                        UUID.randomUUID(),
                        activityId,
                        CLASS_ID,
                        CHILD_ID,
                        scenarioId,
                        1,
                        sessionId,
                        turnId,
                        null,
                        null);
        StructuredRoleplayTurnSteps steps =
                new StructuredRoleplayTurnSteps(
                        roleplayPrompts, llm, trace, input, UUID::randomUUID);
        int from = recorder.size();
        long startedAt = System.nanoTime();
        RoleplayTurnResult result =
                new RetryingRoleplayTurnExecutor()
                        .execute(turnId, steps, RoleplayTurnDeadline.start(turnBudget));
        long totalMs = elapsed(startedAt);
        int calls = calls(from);

        if (!(result instanceof RoleplayTurnResult.Ready ready)) {
            // 전달하지 못한 턴은 대화에 남기지 않고 턴 수도 되돌린다. 같은 말을 다시 해 볼 수 있다.
            turnCount--;
            return new TurnOutcome(null, null, describe(result), totalMs, calls, finished);
        }
        String goalBefore = key(currentGoal);
        dialogue.add(
                new RoleplayTurnInput.DialogueLine(RoleplayTurnInput.Speaker.LEARNER, utterance));
        dialogue.add(
                new RoleplayTurnInput.DialogueLine(
                        RoleplayTurnInput.Speaker.AI, ready.candidate().text()));
        advance(ready.analysis(), ready.candidate());
        return new TurnOutcome(
                ready.candidate().text(),
                new Judgement(
                        goalBefore,
                        ready.analysis().learningState(),
                        ready.analysis().primaryGap().code(),
                        ready.analysis().nextStrategy().type(),
                        ready.candidate().responseType(),
                        ready.candidate().supportLevel(),
                        key(ready.candidate().targetMicroGoalId()),
                        goalStatusLine()),
                null,
                totalMs,
                calls,
                finished);
    }

    /**
     * @param reply 아동에게 전달된 AI 응답. 전달하지 못했으면 null
     * @param failure 전달하지 못한 이유
     */
    record TurnOutcome(
            String reply,
            Judgement judgement,
            String failure,
            long totalMs,
            int calls,
            boolean finished) {}

    /** 한 턴에서 모델이 내린 판단. 화면에 보이지 않는 값이라 따로 보여 준다. */
    record Judgement(
            String goal,
            String learningState,
            String gap,
            String strategy,
            String responseType,
            String supportLevel,
            String targetGoal,
            String goalStatuses) {}

    GeneratedScenario scenario() {
        return scenario;
    }

    int turnCount() {
        return turnCount;
    }

    String supportLevel() {
        return supportLevel;
    }

    String currentGoalKey() {
        return key(currentGoal);
    }

    String goalStatusLine() {
        StringBuilder line = new StringBuilder();
        goalIds.forEach(
                (key, id) -> {
                    if (!line.isEmpty()) line.append(" · ");
                    line.append(key).append(' ').append(goalStatuses.get(id));
                });
        return line.toString();
    }

    List<RecordingLlmProvider.Call> calls() {
        return recorder.since(0);
    }

    private void start(GeneratedScenario generated, String goal) {
        scenario = generated;
        learningGoal = goal;
        Map<String, UUID> ids = new LinkedHashMap<>();
        generated.microGoals().forEach(g -> ids.put(g.id(), UUID.randomUUID()));
        goalIds = ids;
        goalStatuses.clear();
        ids.values().forEach(id -> goalStatuses.put(id, "NOT_OBSERVED"));
        currentGoal = ids.values().iterator().next();
        dialogue.clear();
        supportLevel = null;
        supportTurn = 1;
        promptCount = 0;
        answerRevealed = false;
        turnCount = 0;
        finished = false;
    }

    private RoleplayTurnInput.Scenario scenarioInput() {
        RoleplayTurnInput.Scenario base =
                ScenarioPromptFactory.toRoleplayScenario(scenario, learningGoal, goalIds);
        return new RoleplayTurnInput.Scenario(
                base.learningGoal(),
                base.scenarioLevel(),
                base.scenarioFacts(),
                base.prohibitedInferences(),
                base.microGoals().stream()
                        .map(
                                goal ->
                                        new RoleplayTurnInput.MicroGoal(
                                                goal.id(),
                                                goal.description(),
                                                goal.requiredEvidence(),
                                                goalStatuses.get(goal.id())))
                        .toList());
    }

    /**
     * 이 도구의 상태 규칙(운영 규칙이 아니다).
     *
     * <ul>
     *   <li>원인 분석이 GOAL_ACHIEVED 면 현재 목표를 ACHIEVED, 정답 공개 응답이면 ANSWER_REVEALED, 아니면 IN_PROGRESS 로
     *       둔다.
     *   <li>다음 지원 단계는 실제로 전달된 응답의 support_level 이다.
     *   <li>응답이 다른 목표를 겨냥했거나 현재 목표가 끝났으면 그 목표(끝났으면 다음 남은 목표)로 옮기고 지원 횟수를 새로 센다.
     *   <li>CLOSING 응답, 모든 목표 종료, 턴 상한 중 하나면 역할극을 끝낸다.
     * </ul>
     */
    private void advance(AnalysisResult analysis, CandidateResponse candidate) {
        if ("ANSWER_REVEAL".equals(candidate.responseType())) {
            goalStatuses.put(currentGoal, "ANSWER_REVEALED");
        } else if ("GOAL_ACHIEVED".equals(analysis.learningState())) {
            goalStatuses.put(currentGoal, "ACHIEVED");
        } else {
            goalStatuses.put(currentGoal, "IN_PROGRESS");
        }
        supportLevel = candidate.supportLevel();
        boolean currentDone = done(currentGoal);

        UUID next = currentGoal;
        UUID target = candidate.targetMicroGoalId();
        if (goalStatuses.containsKey(target) && !done(target)) {
            next = target;
        } else if (currentDone) {
            next =
                    goalStatuses.keySet().stream()
                            .filter(id -> !done(id))
                            .findFirst()
                            .orElse(currentGoal);
        }
        if (!next.equals(currentGoal)) {
            currentGoal = next;
            supportTurn = 1;
            promptCount = 0;
            answerRevealed = false;
        } else {
            supportTurn++;
            promptCount++;
            answerRevealed = answerRevealed || "ANSWER_REVEAL".equals(candidate.responseType());
        }
        finished =
                "CLOSING".equals(candidate.responseType())
                        || goalStatuses.keySet().stream().allMatch(this::done)
                        || turnCount >= policy.maximumTurnCount();
    }

    private boolean done(UUID goal) {
        String status = goalStatuses.get(goal);
        return "ACHIEVED".equals(status) || "ANSWER_REVEALED".equals(status);
    }

    private String key(UUID goalId) {
        return goalIds.entrySet().stream()
                .filter(entry -> entry.getValue().equals(goalId))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(String.valueOf(goalId));
    }

    private void requireScenario() {
        if (scenario == null) throw new IllegalStateException("generate a scenario first");
    }

    private int calls(int from) {
        return recorder.since(from).size();
    }

    private static String describe(RoleplayTurnResult result) {
        return switch (result) {
            case RoleplayTurnResult.RecoveryRequired required ->
                    "RECOVERY "
                            + required.operation()
                            + "/"
                            + required.reason()
                            + " (운영에서는 승인된 안내 문구를 보여 준다)";
            case RoleplayTurnResult.TimedOut timedOut -> "TIMED_OUT (" + timedOut.message() + ")";
            default -> result.getClass().getSimpleName();
        };
    }

    /** "CODE: field" 에서 코드만. 구조가 깨진 JSON 은 INVALID_STRUCTURE 로 넘긴다. */
    private static String failureCode(InvalidLlmOutputException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        String code = message.split("[: ]", 2)[0].toUpperCase(Locale.ROOT);
        return code.isBlank() || code.startsWith("LLM") ? "INVALID_STRUCTURE" : code;
    }

    private static long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
