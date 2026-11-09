package com.neuringo.neuringobe.ai.infrastructure.input;

import com.neuringo.neuringobe.ai.application.input.RoleplayPersonalInformationMasker;
import com.neuringo.neuringobe.ai.application.model.*;
import com.neuringo.neuringobe.ai.application.port.LlmProvider;
import com.neuringo.neuringobe.ai.application.roleplay.*;
import com.neuringo.neuringobe.ai.application.structured.*;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/** 개인정보를 먼저 가린 후 의미·안전 판정만 요청하며, 모델이 입력 문장을 재작성하지 않는다. */
public final class ModelRoleplayInputProcessor implements RoleplayInputProcessor {
    private final StructuredLlmExecutor executor;
    private final JacksonLlmOutputParser<Judgment> parser;
    private final JsonMapper mapper;
    private final Function<UUID, String> learnerName;
    private final RoleplayPersonalInformationMasker masker =
            new RoleplayPersonalInformationMasker();
    private final String prompt;

    public ModelRoleplayInputProcessor(
            LlmProvider provider,
            JsonMapper mapper,
            Function<UUID, String> learnerName,
            String prompt) {
        this(provider, mapper, learnerName, prompt, false);
    }

    public ModelRoleplayInputProcessor(
            LlmProvider provider,
            JsonMapper mapper,
            Function<UUID, String> learnerName,
            String prompt,
            boolean observations) {
        this(
                provider,
                mapper,
                learnerName,
                prompt,
                observations,
                com.neuringo.neuringobe.ai.application.port.AiDiagnostics.NONE);
    }

    public ModelRoleplayInputProcessor(
            LlmProvider provider,
            JsonMapper mapper,
            Function<UUID, String> learnerName,
            String prompt,
            boolean observations,
            com.neuringo.neuringobe.ai.application.port.AiDiagnostics diagnostics) {
        this.executor = new StructuredLlmExecutor(provider, observations, diagnostics);
        this.mapper = Objects.requireNonNull(mapper);
        this.parser = new JacksonLlmOutputParser<>(mapper, Judgment.class);
        this.learnerName = Objects.requireNonNull(learnerName);
        this.prompt = Objects.requireNonNull(prompt);
    }

    @Override
    public Result process(String input) {
        // 운영 호출에는 인증된 아동과 공유 기한이 필요하다.
        return new Unavailable();
    }

    @Override
    public Result process(Input input) {
        var deadline = input.deadline();
        deadline.requireActive();
        if (input.trace().childId() == null) return new Unavailable();
        try {
            var masked =
                    deadline.withinBudget(
                            () ->
                                    masker.mask(
                                            input.text(),
                                            learnerName.apply(input.trace().childId())));
            if (masked.text().isBlank() || masked.uncertain()) return new Invalid();
            var request =
                    new LlmRequest(
                            input.trace(),
                            new AiAttemptContext(0, 0, 0),
                            AiOperation.INPUT_VALIDATION,
                            1,
                            prompt,
                            mapper.writeValueAsString(java.util.Map.of("input", masked.text())),
                            "roleplay-input-validation/v1",
                            "input-judgment/v1",
                            "input-minimal-v1",
                            deadline.callBudget());
            var result = deadline.withinBudget(() -> executor.execute(request, parser));
            if (!(result instanceof AiCallResult.Success<Judgment> success))
                return new Unavailable();
            var judgment = success.data();
            return switch (judgment.decision()) {
                case UNSAFE -> new Unsafe();
                case REINPUT -> new Invalid();
                case UNAVAILABLE -> new Unavailable();
                case ANALYZABLE -> {
                    if (!judgment.meaningPreserved() || !judgment.privacyClear())
                        yield new Invalid();
                    yield new Accepted(
                            masked.text(),
                            new Assessment(judgment.confidence(), true, true, masked.changed()));
                }
            };
        } catch (RoleplayTurnDeadline.Expired expired) {
            throw expired;
        } catch (RuntimeException unavailable) {
            deadline.requireActive();
            return new Unavailable();
        }
    }

    public enum Decision {
        ANALYZABLE,
        REINPUT,
        UNSAFE,
        UNAVAILABLE
    }

    public record Judgment(
            Decision decision, Boolean meaningPreserved, Boolean privacyClear, Double confidence) {
        public Judgment {
            Objects.requireNonNull(decision);
            Objects.requireNonNull(meaningPreserved);
            Objects.requireNonNull(privacyClear);
            Objects.requireNonNull(confidence);
            if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
                throw new IllegalArgumentException("입력 판정 신뢰도 범위가 올바르지 않습니다.");
        }
    }
}
