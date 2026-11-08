package com.neuringo.neuringobe.ai.eval;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.TestcontainersConfiguration;
import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.support.TestInstructors;
import com.neuringo.neuringobe.user.repository.UserAccountRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 ML API(엘리스) 통합 실측. 아동 역할극 턴을 HTTP → 서비스 → DB → 실제 LLM·STT 까지 돌려 연동 오류를 모은다.
 *
 * <p>{@code ML_API_REAL=true} 일 때만 돈다(CI 제외). 실행은 {@code ./gradlew mlApiTest -Pmlapi.model=MINI41}
 * 처럼 한다. 모델 주소·키는 backend/.env.local 의 {@code AI_API_KEY}, {@code MODEL_<이름>_URL·_ID·_PRICE}, STT 는
 * {@code MODEL_WHISPER_URL·_ID} 를 읽는다. 결과는 build/mlapi-it/&lt;run&gt;/ 에 남는다.
 *
 * <p>흐름은 실제 API 순서를 따른다: 강사 활동 배정(FIRST_COMBINED) → 공통 승인 시나리오 시드(JDBC, 승인 기록 포함) → 아동 입장·퀴즈 → 강사
 * 시나리오 검토·확정(#101 API) → 역할극 시작 → 텍스트(또는 음성) 턴. 운영 구현이 없는 입력 처리기·음성 전달과 키가 없는 TTS 만 {@link
 * RealMlApiTestConfiguration} 이 대신한다.
 *
 * <p>AI 응답 내용은 단정하지 않는다(docs/testing.md). 계약 불변식(5xx 없음·상태 값·턴 예산·메시지 모양·최대 턴 종료)이 어긋나면 보고서에 남기고
 * 테스트는 통과시킨다. 틀린 동작을 테스트로 굳히지 않으려는 것이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RealMlApiTestConfiguration.class})
@EnabledIfEnvironmentVariable(named = "ML_API_REAL", matches = "true")
@TestPropertySource(
        properties = {
            "roleplay.http.enabled=true",
            "roleplay.text.enabled=true",
            "roleplay.text.maximum-characters=200",
            "roleplay.progress.maximum-turn-count=10",
            "roleplay.progress.maximum-support-turns-per-micro-goal=3",
            "roleplay.http.maximum-upload-bytes=2097152",
            "roleplay.http.maximum-request-bytes=2200000",
            "roleplay.http.allowed-formats=WEBM",
            "roleplay.http.worker-count=2",
            "roleplay.http.queue-capacity=4",
            "roleplay.http.turn-budget=60s",
            "roleplay.retention.cleanup-enabled=false",
            "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long"
        })
class RealMlApiRoleplayIntegrationTest {

    static final String TEACHER = "mlapi-real-teacher";

    /** 배정 목표 제목. 재사용 후보는 승인 기록의 목표 제목이 이것과 같아야 나온다. */
    static final String GOAL_TITLE = "다친 친구의 마음 알아보기";

    static final Path SPOOL = spool();

    /** 합성 아동 발화(실제 아동 발화가 아님). steady·strong·unsure 는 #90 로컬 실측의 대본과 같다. */
    static final Map<String, List<String>> PERSONAS = personas();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired ClassroomRepository classrooms;
    @Autowired ChildRepository children;
    @Autowired RealMlApiTestConfiguration.CallLog log;

    private final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        RealMlApiSettings s = RealMlApiSettings.load();
        r.add("spring.ai.model.chat", () -> "openai");
        r.add("spring.ai.openai.base-url", s::chatBaseUrl);
        r.add("spring.ai.openai.api-key", s::apiKey);
        r.add("spring.ai.openai.chat.model", s::modelId);
        r.add("spring.ai.openai.timeout", s::timeout);
        r.add("spring.ai.openai.max-retries", () -> "0");
        r.add("neuringo.ai.provider-name", () -> "elice-mlapi");
        r.add("neuringo.ai.model", s::modelId);
        r.add("neuringo.ai.request-timeout", s::timeout);
        r.add("neuringo.ai.max-retries", () -> "0");
        r.add("neuringo.ai.stt.provider", () -> "openai");
        r.add("neuringo.ai.stt.provider-name", () -> "elice-mlapi");
        r.add("neuringo.ai.stt.base-url", s::sttBaseUrl);
        r.add("neuringo.ai.stt.api-key", s::apiKey);
        r.add("neuringo.ai.stt.model", s::sttModel);
        r.add("roleplay.http.spool-directory", SPOOL::toString);
    }

    @Test
    void runsRoleplayTurnsAgainstTheRealMlApi() throws Exception {
        RealMlApiSettings s = RealMlApiSettings.load();
        Path out = Path.of("build", "mlapi-it", s.runId());
        Files.createDirectories(out);
        List<RealMlApiRunReport.Run> runs = new ArrayList<>();
        String stopReason = null;
        outer:
        for (String persona : s.personas()) {
            List<String> inputs = PERSONAS.get(persona);
            if (inputs == null) inputs = voiceInputs(s, persona);
            for (int repeat = 1; repeat <= s.repeats(); repeat++) {
                if (cost(s) >= s.budgetKrw()) {
                    stopReason = "budget";
                    break outer;
                }
                runs.add(runOnce(s, persona, repeat, inputs));
                RealMlApiRunReport.write(
                        out, s, runs, log.allLlm(), log.sttAll(), stopReason, log.rejections());
            }
        }
        RealMlApiRunReport.write(
                out, s, runs, log.allLlm(), log.sttAll(), stopReason, log.rejections());
        System.out.println("MLAPI report: " + out.toAbsolutePath().resolve("summary.md"));
    }

    private RealMlApiRunReport.Run runOnce(
            RealMlApiSettings s, String persona, int repeat, List<String> inputs) {
        var run = new RealMlApiRunReport.Run(persona, repeat);
        try {
            UUID childId = prepareChild();
            MockHttpSession child = enterChild(childId);
            UUID activityId = assignFirstCombined(childId);
            run.activityId = activityId;
            seedApprovedScenarios(activityId, persona + "-" + repeat);
            solveQuiz(activityId, child);
            confirmScenario(activityId);
            Map<String, Object> started = startRoleplay(activityId, child);
            UUID sessionId = UUID.fromString((String) started.get("sessionId"));
            run.sessionId = sessionId;
            run.scenarioLevel =
                    jdbc.queryForObject(
                            "select sc.scenario_level from roleplay_session rs join scenario sc on sc.scenario_id = rs.scenario_id where rs.session_id = ?",
                            String.class,
                            sessionId);
            run.openingMessages = messages(started.get("delivery"));
            int maximumInputs = 14;
            for (int i = 0; i < maximumInputs; i++) {
                String status = activityStatus(activityId);
                if ("COMPLETED".equals(status)) {
                    run.endReason = "activity COMPLETED";
                    break;
                }
                if (cost(s) >= s.budgetKrw()) {
                    run.endReason = "budget";
                    break;
                }
                String input = inputs.get(i % inputs.size());
                run.turns.add(turn(s, child, sessionId, i + 1, input));
            }
            if (run.endReason == null) run.endReason = "inputs exhausted";
            run.finalActivityStatus = activityStatus(activityId);
            Map<String, Object> session =
                    jdbc.queryForMap(
                            "select status, last_turn_number from roleplay_session where session_id = ?",
                            sessionId);
            run.sessionStatus = String.valueOf(session.get("status"));
            run.lastTurnNumber = ((Number) session.get("last_turn_number")).intValue();
            run.committedTurns =
                    jdbc.queryForObject(
                            "select count(*) from roleplay_turn where session_id = ?",
                            Integer.class,
                            sessionId);
        } catch (Exception | AssertionError e) {
            run.setupError = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return run;
    }

    private RealMlApiRunReport.Turn turn(
            RealMlApiSettings s, MockHttpSession child, UUID sessionId, int index, String input)
            throws Exception {
        int llmFrom = log.llmSize();
        int sttFrom = log.sttSize();
        boolean voice = input.startsWith("voice:");
        UUID key = UUID.randomUUID();
        RequestBuilder request =
                voice
                        ? voiceRequest(s, sessionId, input.substring(6))
                                .session(child)
                                .with(csrf())
                                .header("Idempotency-Key", key)
                        : textRequest(sessionId, input)
                                .session(child)
                                .with(csrf())
                                .header("Idempotency-Key", key);
        long startedAt = System.nanoTime();
        MvcResult result = mvc.perform(request).andReturn();
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var turn = new RealMlApiRunReport.Turn(index, voice ? "voice" : "text", input);
        turn.httpStatus = result.getResponse().getStatus();
        turn.latencyMs = latencyMs;
        turn.status = read(body, "$.data.status");
        turn.action = read(body, "$.data.action");
        turn.errorCode = read(body, "$.error.code");
        turn.acceptedInput = read(body, "$.data.acceptedInput");
        Object messages = readObject(body, "$.data.messages");
        turn.messages = messages(messages);
        turn.llmCalls = log.llmSince(llmFrom);
        turn.sttCalls = log.sttSince(sttFrom);
        return turn;
    }

    private MockHttpServletRequestBuilder textRequest(UUID sessionId, String text) {
        return post("/api/v1/roleplay-sessions/{id}/text-inputs", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("text", text)));
    }

    private MockMultipartHttpServletRequestBuilder voiceRequest(
            RealMlApiSettings s, UUID sessionId, String fileName) throws IOException {
        Path file = s.voiceDir().resolve(fileName);
        String mime = fileName.endsWith(".wav") ? "audio/wav" : "audio/webm";
        return multipart("/api/v1/roleplay-sessions/{id}/voice-inputs", sessionId)
                .file(new MockMultipartFile("audio", fileName, mime, Files.readAllBytes(file)));
    }

    // --- 준비: 강사·학급·아동은 저장소로, 활동·입장·퀴즈·시작은 실제 API 로 ---

    private UUID prepareChild() {
        TestInstructors.save(users, TEACHER);
        UUID classroomId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        classrooms.save(
                new Classroom(
                        classroomId, TestInstructors.id(TEACHER), "실측반", ClassroomStatus.ACTIVE));
        children.save(new Child(childId, classroomId, "실측 아동", ChildStatus.ACTIVE));
        return childId;
    }

    private MockHttpSession enterChild(UUID childId) throws Exception {
        MvcResult code =
                expect(
                        mvc.perform(
                                        post("/api/v1/children/{childId}/access-codes", childId)
                                                .with(TestInstructors.instructor(TEACHER))
                                                .with(csrf())
                                                .header("Idempotency-Key", UUID.randomUUID()))
                                .andReturn(),
                        201);
        String accessCode = JsonPath.read(body(code), "$.data.accessCode");
        MvcResult entry =
                expect(
                        mvc.perform(
                                        post("/api/v1/child-access-sessions")
                                                .with(csrf())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of("accessCode", accessCode))))
                                .andReturn(),
                        201);
        return (MockHttpSession) entry.getRequest().getSession(false);
    }

    private UUID assignFirstCombined(UUID childId) throws Exception {
        MvcResult created =
                expect(
                        mvc.perform(
                                        post("/api/v1/activities")
                                                .with(TestInstructors.instructor(TEACHER))
                                                .with(csrf())
                                                .header("Idempotency-Key", UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "childId",
                                                                        childId,
                                                                        "activityType",
                                                                        "FIRST_COMBINED",
                                                                        "goal",
                                                                        Map.of(
                                                                                "title",
                                                                                GOAL_TITLE)))))
                                .andReturn(),
                        201);
        return UUID.fromString(JsonPath.read(body(created), "$.data.activityId"));
    }

    /**
     * 공통 승인 시나리오 L1·L2·L3 를 넣는다. 내용은 #90 로컬 실측의 scripts/local/seed-approved-scenarios.sql 과 같다. 강사
     * 검토 API(#101)는 승인 기록(scenario_approval)이 있는 승인본만 재사용 후보로 내므로 그 행도 넣는다. 없으면 후보가 0개라 LLM 이 새
     * 시나리오를 만들어 회차·모델마다 시나리오가 달라진다.
     */
    private void seedApprovedScenarios(UUID activityId, String tag) {
        Map<String, Object> owner =
                jdbc.queryForMap(
                        "select c.class_id, g.content_hash from activity a join child c on c.child_id = a.child_id join learning_goal g on g.goal_id = a.goal_id where a.activity_id = ?",
                        activityId);
        for (String level : List.of("L1", "L2", "L3")) {
            UUID scenarioId = UUID.randomUUID();
            jdbc.update(
                    """
                    insert into scenario (scenario_id, status, scenario_level, goal_content_hash, title, summary,
                        ai_role, characters, scenario_facts, prohibited_inferences, opening_prompts, origin,
                        prompt_version, policy_version, created_at, class_id, scenario_version)
                    values (?, 'DRAFT', ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, 'PREPARED',
                        'mlapi-it-seed/v1', 'mlapi-it-seed/v1', now(), ?, 1)
                    """,
                    scenarioId,
                    level,
                    owner.get("content_hash"),
                    "놀이터에서 넘어진 친구 " + level + " (" + tag + ")",
                    "쉬는 시간에 놀이터에서 친구 민준이가 뛰다가 넘어졌다. 아동은 민준이의 마음을 알아보고 위로하는 말을 연습한다.",
                    "넘어져서 무릎을 다친 친구 민준",
                    "[{\"id\": \"C1\", \"role\": \"넘어진 친구 민준\"}]",
                    "[\"민준이가 놀이터에서 뛰다가 넘어졌다\", \"민준이 무릎에서 피가 조금 난다\", \"민준이가 무릎을 잡고 울먹인다\"]",
                    "[\"민준이가 일부러 넘어졌다고 단정하지 않는다\", \"민준이의 성격을 판단하지 않는다\"]",
                    "{\"S0\": \"민준이가 놀이터에서 넘어져서 무릎을 잡고 있어. 민준이는 지금 어떤 마음일까?\","
                            + " \"S1\": \"민준이 무릎에서 피가 나고 눈물이 글썽글썽해. 민준이는 지금 어떤 마음일까?\","
                            + " \"S2\": \"민준이가 넘어져서 울먹이고 있어. 민준이는 지금 속상할까, 신날까?\"}",
                    owner.get("class_id"));
            String[][] goals = {
                {"감정 이름 말하기", "[\"민준이가 아프거나 속상하다는 감정\"]", "민준이가 아프고 속상할 것 같아"},
                {"감정의 이유 말하기", "[\"넘어져서 무릎을 다쳤다는 이유\"]", "넘어져서 무릎이 아파서 속상해"},
                {"위로하는 말 하기", "[\"괜찮은지 묻거나 걱정하는 말\"]", "민준아 괜찮아? 많이 아프지?"},
                {"도움 행동 말하기", "[\"선생님께 알리거나 일으켜 주는 행동\"]", "내가 선생님한테 같이 가 줄게"}
            };
            int count = "L3".equals(level) ? 4 : 3;
            UUID first = null;
            for (int order = 1; order <= count; order++) {
                UUID microGoalId = UUID.randomUUID();
                if (order == 1) first = microGoalId;
                String[] g = goals[order - 1];
                jdbc.update(
                        "insert into scenario_micro_goal (micro_goal_id, scenario_id, order_no, description, required_evidence, achievement_example) values (?, ?, ?, ?, ?::jsonb, ?)",
                        microGoalId,
                        scenarioId,
                        order,
                        g[0],
                        g[1],
                        g[2]);
            }
            for (String support : List.of("S0", "S1", "S2")) {
                jdbc.update(
                        "insert into scenario_opening_focus (scenario_id, support_level, micro_goal_id) values (?, ?, ?)",
                        scenarioId,
                        support,
                        first);
            }
            jdbc.update(
                    "update scenario set status = 'APPROVED' where scenario_id = ?", scenarioId);
            // 트리거(guard_scenario_approval)가 APPROVED·같은 레벨을 확인하므로 승인 뒤에 넣는다.
            jdbc.update(
                    "insert into scenario_approval (scenario_id, instructor_id, goal_title, scenario_level, verification_rule_version, approved_at) values (?, ?, ?, ?, 1, now())",
                    scenarioId,
                    TestInstructors.id(TEACHER),
                    GOAL_TITLE,
                    level);
        }
    }

    /**
     * 강사가 시나리오를 확정한다(#101). 퀴즈로 정해진 레벨의 재사용 후보를 받고(START_OR_RESUME) 그 후보로 확정한다. 위에서 넣은 승인본이 후보로
     * 나오므로 LLM 생성 호출은 없다. 확정해야 activity.scenario_id 가 채워져 역할극을 시작할 수 있다.
     */
    private void confirmScenario(UUID activityId) throws Exception {
        MvcResult review =
                expect(
                        mvc.perform(
                                        post("/api/v1/activities/{id}/scenario-review", activityId)
                                                .with(TestInstructors.instructor(TEACHER))
                                                .with(csrf())
                                                .header("Idempotency-Key", UUID.randomUUID())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json.writeValueAsString(
                                                                Map.of(
                                                                        "action",
                                                                        "START_OR_RESUME"))))
                                .andReturn(),
                        200);
        String offered = body(review);
        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("reviewId", JsonPath.read(offered, "$.data.reviewId"));
        confirmation.put(
                "revision", ((Number) JsonPath.read(offered, "$.data.revision")).longValue());
        confirmation.put("scenarioId", JsonPath.read(offered, "$.data.scenario.scenarioId"));
        confirmation.put(
                "scenarioVersion",
                ((Number) JsonPath.read(offered, "$.data.scenario.scenarioVersion")).intValue());
        expect(
                mvc.perform(
                                put("/api/v1/activities/{id}/scenario", activityId)
                                        .with(TestInstructors.instructor(TEACHER))
                                        .with(csrf())
                                        .header("Idempotency-Key", UUID.randomUUID())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(confirmation)))
                        .andReturn(),
                200);
    }

    /** 늘 첫 보기로 답한다. 틀리면 힌트 두 번 뒤 마지막 보기로 마친다(#90 로컬 드라이버와 같다). */
    private void solveQuiz(UUID activityId, MockHttpSession child) throws Exception {
        MvcResult items =
                expect(
                        mvc.perform(
                                        get("/api/v1/activities/{id}/quiz-items", activityId)
                                                .session(child))
                                .andReturn(),
                        200);
        List<Map<String, Object>> list = JsonPath.read(body(items), "$.data");
        for (Map<String, Object> item : list) {
            String quizId = String.valueOf(item.get("activityQuizId"));
            @SuppressWarnings("unchecked")
            List<String> choices = (List<String>) item.get("choices");
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("firstResponse", choices.getFirst());
            if ("SELF_EMOTION_SITUATION".equals(item.get("quizType"))) {
                first.put("expressionMatchResult", "MATCH");
                first.put("cameraModelVersion", "mlapi-it/v1");
            }
            MvcResult attempt =
                    putJson(child, "/api/v1/activity-quiz-items/{id}/attempt", quizId, first);
            if (!"FINALIZED".equals(read(body(attempt), "$.data.status"))) {
                for (int hint = 0; hint < 2; hint++) {
                    expect(
                            mvc.perform(
                                            post("/api/v1/activity-quiz-items/{id}/hints", quizId)
                                                    .session(child)
                                                    .with(csrf())
                                                    .header("Idempotency-Key", UUID.randomUUID()))
                                    .andReturn(),
                            200,
                            201);
                }
                Map<String, Object> last = new LinkedHashMap<>(first);
                last.put("finalResponse", choices.getLast());
                putJson(child, "/api/v1/activity-quiz-items/{id}/attempt", quizId, last);
            }
        }
    }

    private MvcResult putJson(
            MockHttpSession child, String uri, String id, Map<String, Object> payload)
            throws Exception {
        return expect(
                mvc.perform(
                                put(uri, id)
                                        .session(child)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(payload)))
                        .andReturn(),
                200,
                201);
    }

    /** 강사 확정 직후라 바로 열려야 한다. 준비 근거 반영이 늦는 경우만 대비해 NOT_READY 면 최대 30초 다시 시도한다. */
    private Map<String, Object> startRoleplay(UUID activityId, MockHttpSession child)
            throws Exception {
        String last = null;
        for (int i = 0; i < 30; i++) {
            MvcResult result =
                    mvc.perform(
                                    post("/api/v1/activities/{id}/roleplay-sessions", activityId)
                                            .session(child)
                                            .with(csrf())
                                            .header("Idempotency-Key", UUID.randomUUID()))
                            .andReturn();
            int status = result.getResponse().getStatus();
            String body = body(result);
            if (status == 200 || status == 201) return JsonPath.read(body, "$.data");
            last = status + " " + read(body, "$.error.code");
            if (!body.contains("INITIAL_ROLEPLAY_NOT_READY")) break;
            Thread.sleep(1000);
        }
        throw new IllegalStateException("roleplay start failed: " + last);
    }

    private String activityStatus(UUID activityId) throws Exception {
        MvcResult result =
                mvc.perform(
                                get("/api/v1/activities/{id}/status", activityId)
                                        .with(TestInstructors.instructor(TEACHER)))
                        .andReturn();
        return read(body(result), "$.data.status");
    }

    // --- 도우미 ---

    private double cost(RealMlApiSettings s) {
        return RealMlApiRunReport.cost(log.allLlm(), s);
    }

    private static MvcResult expect(MvcResult result, int... statuses) throws Exception {
        int actual = result.getResponse().getStatus();
        if (Arrays.stream(statuses).noneMatch(x -> x == actual)) {
            throw new AssertionError(
                    result.getRequest().getMethod()
                            + " "
                            + result.getRequest().getRequestURI()
                            + " -> "
                            + actual
                            + " "
                            + body(result));
        }
        return result;
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String read(String body, String path) {
        Object value = readObject(body, path);
        return value == null ? null : String.valueOf(value);
    }

    private static Object readObject(String body, String path) {
        if (body == null || body.isBlank()) return null;
        try {
            return JsonPath.read(body, path);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> messages(Object delivery) {
        Object list = delivery;
        if (delivery instanceof Map<?, ?> map) list = map.get("messages");
        if (!(list instanceof List<?> items)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private static List<String> voiceInputs(RealMlApiSettings s, String persona) {
        if (!"voice".equals(persona) || s.voiceDir() == null) {
            throw new IllegalArgumentException("unknown persona: " + persona);
        }
        try (var files = Files.list(s.voiceDir())) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".webm") || n.endsWith(".wav"))
                    .sorted()
                    .map(n -> "voice:" + n)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path spool() {
        try {
            return Files.createTempDirectory("neuringo-mlapi-it-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, List<String>> personas() {
        Map<String, List<String>> p = new LinkedHashMap<>();
        p.put(
                "steady",
                List.of(
                        "음 모르겠어",
                        "민준이 아플 것 같아. 속상해",
                        "넘어져서",
                        "넘어져서 무릎에서 피가 나서 아파서 그래",
                        "모르겠어",
                        "민준아 괜찮아? 많이 아프지?",
                        "선생님한테 같이 가 줄게",
                        "괜찮아 내가 도와줄게",
                        "민준아 일어날 수 있어?",
                        "괜찮아?"));
        p.put(
                "strong",
                List.of(
                        "민준이는 넘어져서 아프고 속상할 것 같아",
                        "넘어져서 무릎을 다쳐서 피가 나니까 아프고 속상한 거야",
                        "민준아 괜찮아? 많이 아프지? 걱정돼",
                        "내가 선생님한테 알리고 같이 가 줄게",
                        "괜찮아 내가 일으켜 줄게",
                        "민준아 많이 아팠지? 이제 괜찮아",
                        "선생님께 말씀드리자",
                        "같이 가자"));
        p.put(
                "unsure",
                List.of(
                        "모르겠어",
                        "모르겠어",
                        "속상해",
                        "모르겠어",
                        "넘어져서 아파서",
                        "모르겠어",
                        "모르겠어",
                        "괜찮아?",
                        "모르겠어",
                        "선생님한테 같이 가 줄게"));
        // 경계 입력: 빈 칸·공백·최대 글자(200)·초과(201)·영어·이모지·숫자·주제 이탈·거친 말·전화번호 꼴.
        p.put(
                "edge",
                List.of(
                        "",
                        "   ",
                        "가".repeat(200),
                        "가".repeat(201),
                        "I don't know",
                        "😀😀😀",
                        "123456",
                        "오늘 점심 뭐 먹었어?",
                        "바보야 저리 가",
                        "내 번호는 010-1234-5678 이야"));
        return p;
    }

    static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMdd-HHmm", Locale.ROOT));
    }
}
