package com.neuringo.neuringobe.ai.application.prompt;

import com.neuringo.neuringobe.ai.application.structured.InvalidLlmOutputException;
import com.neuringo.neuringobe.ai.application.structured.JacksonLlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.LlmOutputParser;
import com.neuringo.neuringobe.ai.application.structured.output.GeneratedScenario;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * 시나리오 생성 프롬프트 v1 출력의 계약 검사(SCN-08·SCN-10). 형식은 맞지만 이 요청과 맞지 않는 출력을 InvalidLlmOutputException 으로
 * 막는다. StructuredLlmExecutor 는 이를 INVALID_OUTPUT_FORMAT 으로 돌려준다.
 *
 * <p>예외 메시지는 {@code ScenarioFailure} 이름으로 시작한다. 재생성 요청의 failure_codes 로 그대로 쓸 수 있다. 메시지에는 모델 출력 내용을
 * 넣지 않는다.
 *
 * <p>금지 표현·위험 낱말 검사는 prohibited_inferences 를 빼고 한다. 금지 추론은 "~라고 단정하지 않는다"처럼 하면 안 되는 일을 적는 칸이라, 그
 * 낱말이 들어가는 것이 정상이다.
 */
final class ScenarioOutputParser {

    /** 검사 실패 코드. 재생성 요청의 failure_codes 로 넘긴다. */
    enum ScenarioFailure {
        LEVEL_MISMATCH,
        AI_ROLE_INVALID,
        CHARACTERS_INVALID,
        CHARACTER_ID_IN_TEXT,
        FACT_COUNT_OUT_OF_RANGE,
        DUPLICATE_FACT,
        PROHIBITED_INFERENCE_COUNT_OUT_OF_RANGE,
        MICRO_GOAL_COUNT_OUT_OF_RANGE,
        MICRO_GOAL_ID_INVALID,
        EVIDENCE_COUNT_OUT_OF_RANGE,
        STARTING_QUESTIONS_INVALID,
        QUESTION_FORMAT_INVALID,
        TOO_LONG,
        FORBIDDEN_EXPRESSION_USED,
        UNSAFE_CONTENT,
        PERSONAL_INFORMATION
    }

    /** 난이도별 개수(프롬프트의 "난이도별 규모" 표와 같다). */
    record LevelRule(int minFacts, int maxFacts, int minGoals, int maxGoals) {}

    static final Map<String, LevelRule> LEVEL_RULES =
            Map.of(
                    "L1", new LevelRule(3, 4, 3, 3),
                    "L2", new LevelRule(4, 5, 3, 4),
                    "L3", new LevelRule(5, 6, 4, 4));

    /** 지원 수준별 시작 질문. S3(정답 제공)로 시작하는 역할극은 없어서 만들지 않는다. */
    static final Set<String> STARTING_SUPPORT_LEVELS = Set.of("S0", "S1", "S2");

    static final String NARRATOR = "NARRATOR";
    static final String LEARNER = "LEARNER";

    /** 프롬프트가 요구하는 길이보다 넉넉하게 둔 상한(공백 포함). 넘으면 형식 실패다. */
    static final int MAX_TITLE_LENGTH = 30;

    static final int MAX_SUMMARY_LENGTH = 120;
    static final int MAX_SENTENCE_LENGTH = 60;
    static final int MAX_QUESTION_LENGTH = RoleplayPromptFactory.MAX_RESPONSE_LENGTH;

    /**
     * 아동용 시나리오에 쓰지 않는 낱말(폭력·죽음·위험 행동·진단 표현). 강사가 막지 않아도 늘 막는다. 최종 목록은 안전 정책(SAFE) 담당과 정한다. 낱말 일부로
     * 다른 말이 걸리지 않게 활용형으로 적었다.
     */
    static final List<String> UNSAFE_TERMS =
            List.of(
                    "죽었", "죽어", "죽을", "죽이", "자살", "자해", "살인", "피가", "칼로", "칼을", "총을", "총알", "폭력",
                    "때리", "때렸", "때려", "학대", "마약", "담배", "소주", "맥주", "장애가", "장애를", "장애인", "adhd",
                    "자폐");

    private static final Pattern MICRO_GOAL_ID = Pattern.compile("MG-\\d{2}");
    private static final Pattern CHARACTER_ID = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern PHONE_NUMBER = Pattern.compile("\\d{2,4}-\\d{3,4}-\\d{4}");
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{6,}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ScenarioOutputParser() {}

    static LlmOutputParser<GeneratedScenario> create(
            ObjectMapper objectMapper, ScenarioGenerationInput input) {
        LlmOutputParser<GeneratedScenario> delegate =
                new JacksonLlmOutputParser<>(objectMapper, GeneratedScenario.class);
        String level = input.difficulty().scenarioLevel();
        List<String> forbidden = input.teacherInput().forbiddenExpressions();
        return content -> {
            GeneratedScenario scenario =
                    delegate.parse(RoleplayOutputParsers.stripCodeFence(content));
            validate(scenario, level, forbidden);
            return scenario;
        };
    }

    static void validate(GeneratedScenario scenario, String level, List<String> forbidden) {
        check(level.equals(scenario.scenarioLevel()), ScenarioFailure.LEVEL_MISMATCH, "level");
        checkCharacters(scenario);

        LevelRule rule = LEVEL_RULES.get(level);
        int facts = scenario.scenarioFacts().size();
        check(
                facts >= rule.minFacts() && facts <= rule.maxFacts(),
                ScenarioFailure.FACT_COUNT_OUT_OF_RANGE,
                "scenario_facts");
        check(
                new HashSet<>(scenario.scenarioFacts()).size() == facts,
                ScenarioFailure.DUPLICATE_FACT,
                "scenario_facts");
        int inferences = scenario.prohibitedInferences().size();
        check(
                inferences >= 1 && inferences <= 3,
                ScenarioFailure.PROHIBITED_INFERENCE_COUNT_OUT_OF_RANGE,
                "prohibited_inferences");

        int goals = scenario.microGoals().size();
        check(
                goals >= rule.minGoals() && goals <= rule.maxGoals(),
                ScenarioFailure.MICRO_GOAL_COUNT_OUT_OF_RANGE,
                "micro_goals");
        for (int i = 0; i < goals; i++) {
            GeneratedScenario.MicroGoal goal = scenario.microGoals().get(i);
            check(
                    goal.id().equals(String.format(Locale.ROOT, "MG-%02d", i + 1))
                            && MICRO_GOAL_ID.matcher(goal.id()).matches(),
                    ScenarioFailure.MICRO_GOAL_ID_INVALID,
                    "micro_goals.id");
            int evidence = goal.requiredEvidence().size();
            check(
                    evidence >= 1 && evidence <= 3,
                    ScenarioFailure.EVIDENCE_COUNT_OUT_OF_RANGE,
                    "micro_goals.required_evidence");
        }

        checkStartingQuestions(scenario.startingQuestions(), !NARRATOR.equals(scenario.aiRole()));
        checkLengths(scenario);

        Set<String> characterIds =
                new HashSet<>(
                        scenario.characters().stream()
                                .map(GeneratedScenario.Character::id)
                                .toList());
        for (String text : allTexts(scenario)) {
            check(
                    characterIds.stream().noneMatch(id -> containsWord(text, id)),
                    ScenarioFailure.CHARACTER_ID_IN_TEXT,
                    "character id");
        }

        List<String> checkedTexts = checkedTexts(scenario);
        for (String term : forbidden) {
            String needle = normalize(term);
            check(
                    checkedTexts.stream().noneMatch(text -> normalize(text).contains(needle)),
                    ScenarioFailure.FORBIDDEN_EXPRESSION_USED,
                    "teacher forbidden expression");
        }
        for (String term : UNSAFE_TERMS) {
            check(
                    checkedTexts.stream().noneMatch(text -> normalize(text).contains(term)),
                    ScenarioFailure.UNSAFE_CONTENT,
                    "unsafe term");
        }
        for (String text : allTexts(scenario)) {
            check(
                    !PHONE_NUMBER.matcher(text).find() && !LONG_DIGITS.matcher(text).find(),
                    ScenarioFailure.PERSONAL_INFORMATION,
                    "number pattern");
        }
    }

    private static void checkCharacters(GeneratedScenario scenario) {
        List<GeneratedScenario.Character> characters = scenario.characters();
        check(
                characters.size() >= 2 && LEARNER.equals(characters.get(0).id()),
                ScenarioFailure.CHARACTERS_INVALID,
                "characters");
        Set<String> ids = new HashSet<>();
        for (GeneratedScenario.Character character : characters) {
            check(
                    CHARACTER_ID.matcher(character.id()).matches() && ids.add(character.id()),
                    ScenarioFailure.CHARACTERS_INVALID,
                    "characters.id");
        }
        String role = scenario.aiRole();
        check(
                NARRATOR.equals(role) || (!LEARNER.equals(role) && ids.contains(role)),
                ScenarioFailure.AI_ROLE_INVALID,
                "ai_role");
        // AI 가 연기하는 인물은 대화 내내 같은 성격·말투를 지켜야 해서 둘 다 있어야 한다.
        characters.stream()
                .filter(character -> character.id().equals(role))
                .forEach(
                        character ->
                                check(
                                        hasText(character.personality())
                                                && hasText(character.speechStyle()),
                                        ScenarioFailure.CHARACTERS_INVALID,
                                        "characters.persona"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 안내자의 시작 질문은 질문 하나로 끝난다. 캐릭터의 첫 대사는 묻지 않아도 되고, 묻는다면 한 번만 묻는다. */
    private static void checkStartingQuestions(
            Map<String, String> questions, boolean characterVoice) {
        check(
                questions.keySet().equals(STARTING_SUPPORT_LEVELS),
                ScenarioFailure.STARTING_QUESTIONS_INVALID,
                "starting_questions");
        for (String question : questions.values()) {
            String text = question == null ? "" : question.strip();
            long marks = text.chars().filter(ch -> ch == '?').count();
            check(
                    characterVoice
                            ? !text.isEmpty() && marks <= 1
                            : marks == 1 && text.endsWith("?"),
                    ScenarioFailure.QUESTION_FORMAT_INVALID,
                    "starting_questions");
            check(
                    text.length() <= MAX_QUESTION_LENGTH,
                    ScenarioFailure.TOO_LONG,
                    "starting_questions");
        }
    }

    private static void checkLengths(GeneratedScenario scenario) {
        check(scenario.title().length() <= MAX_TITLE_LENGTH, ScenarioFailure.TOO_LONG, "title");
        check(
                scenario.summary().length() <= MAX_SUMMARY_LENGTH,
                ScenarioFailure.TOO_LONG,
                "summary");
        List<String> sentences = new ArrayList<>(scenario.scenarioFacts());
        for (GeneratedScenario.MicroGoal goal : scenario.microGoals()) {
            sentences.add(goal.description());
            sentences.addAll(goal.requiredEvidence());
            sentences.add(goal.achievementExample());
        }
        for (String sentence : sentences) {
            check(
                    sentence != null && sentence.length() <= MAX_SENTENCE_LENGTH,
                    ScenarioFailure.TOO_LONG,
                    "sentence");
        }
    }

    /** 금지 표현·위험 낱말을 찾는 칸. 금지 추론은 뺀다(클래스 설명 참고). */
    private static List<String> checkedTexts(GeneratedScenario scenario) {
        List<String> texts = new ArrayList<>();
        texts.add(scenario.title());
        texts.add(scenario.summary());
        scenario.characters().forEach(character -> texts.add(character.role()));
        texts.addAll(scenario.scenarioFacts());
        for (GeneratedScenario.MicroGoal goal : scenario.microGoals()) {
            texts.add(goal.description());
            texts.addAll(goal.requiredEvidence());
            texts.add(goal.achievementExample());
        }
        texts.addAll(scenario.startingQuestions().values());
        return texts;
    }

    private static List<String> allTexts(GeneratedScenario scenario) {
        List<String> texts = checkedTexts(scenario);
        texts.addAll(scenario.prohibitedInferences());
        return texts;
    }

    /** 아동에게 읽히는 문장에 FRIEND 같은 내부 id 가 그대로 들어갔는지. 다른 영문 낱말의 일부는 걸리지 않게 앞뒤가 영문·숫자가 아닐 때만 본다. */
    private static boolean containsWord(String text, String id) {
        return text != null
                && Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(id) + "(?![A-Za-z0-9_])")
                        .matcher(text)
                        .find();
    }

    private static String normalize(String text) {
        return text == null ? "" : WHITESPACE.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    }

    private static void check(boolean condition, ScenarioFailure failure, String field) {
        if (!condition) {
            throw new InvalidLlmOutputException(failure.name() + ": " + field, null);
        }
    }
}
