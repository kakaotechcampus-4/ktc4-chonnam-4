package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.common.validation.TextRules;
import com.neuringo.neuringobe.goal.dto.LearningGoalResponse;
import com.neuringo.neuringobe.goal.service.LearningGoalService;
import com.neuringo.neuringobe.scenario.domain.Scenario;
import com.neuringo.neuringobe.scenario.domain.ScenarioMicroGoal;
import com.neuringo.neuringobe.scenario.domain.ScenarioStatus;
import com.neuringo.neuringobe.scenario.dto.CreateScenarioVersionRequest;
import com.neuringo.neuringobe.scenario.dto.DraftMicroGoalPatch;
import com.neuringo.neuringobe.scenario.dto.ReviewScenarioRequest;
import com.neuringo.neuringobe.scenario.dto.ScenarioResponse;
import com.neuringo.neuringobe.scenario.repository.ScenarioMicroGoalRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [수정하기]: 재사용 후보(공용 승인본)를 이 아동의 목표에 맞게 고칠 새 판(초안)을 만들고, 담당 강사가 고친 뒤 승인·반려·폐기한다.
 *
 * <ul>
 *   <li>초안은 그 아동의 학습 목표에 매인다. 목표의 담당 강사만 보고 고친다. 남의 초안은 없는 것과 같은 404 로 숨긴다.
 *   <li>초안에서 고칠 수 있는 칸은 제목·요약·작은 목표(설명·필요 증거)다. 목표를 더하거나 빼지 않는다.
 *   <li>승인하면 공용 재사용 후보가 되고, 그 목표로 만든 최초 결합 활동에 붙는다. 원본은 그대로 남는다.
 * </ul>
 */
@Service
public class ScenarioDraftService {
    private final ScenarioRepository scenarios;
    private final ScenarioMicroGoalRepository microGoals;
    private final LearningGoalService goals;
    private final InitialScenarioPreparationService preparations;
    private final ActivityRepository activities;
    private final ChildRepository children;
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final Clock clock;

    public ScenarioDraftService(
            ScenarioRepository scenarios,
            ScenarioMicroGoalRepository microGoals,
            LearningGoalService goals,
            InitialScenarioPreparationService preparations,
            ActivityRepository activities,
            ChildRepository children,
            JdbcTemplate jdbc,
            EntityManager entityManager,
            Clock clock) {
        this.scenarios = scenarios;
        this.microGoals = microGoals;
        this.goals = goals;
        this.preparations = preparations;
        this.activities = activities;
        this.children = children;
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    public record Created(ScenarioResponse scenario, boolean created) {}

    /** 승인본을 고칠 새 판을 만든다. 같은 목표에 같은 원본으로 만든 초안이 이미 있으면 그것을 돌려준다. */
    @Transactional
    public Created createVersion(
            UUID sourceId, CreateScenarioVersionRequest request, Authentication authentication) {
        Scenario source = scenarios.findById(sourceId).orElseThrow(this::notFound);
        UUID goalId = request.goalId() != null ? request.goalId() : source.getGoalId();
        if (goalId == null) {
            throw invalid("공용 시나리오를 고치려면 goalId 가 필요합니다.");
        }
        if (source.getGoalId() != null && !source.getGoalId().equals(goalId)) {
            throw invalid("원본과 다른 목표로 새 판을 만들 수 없습니다.");
        }
        LearningGoalResponse goal;
        try {
            goal = goals.get(goalId, authentication);
        } catch (ResourceNotFoundException hidden) {
            // 남의 목표로는 만들 수 없다. 다른 시나리오 API 처럼 없는 시나리오와 같게 숨긴다.
            throw notFound();
        }
        // 남의 초안·판정 기록은 공용이 아니라 보이지 않는다.
        if (source.getStatus() != ScenarioStatus.APPROVED) {
            if (source.getGoalId() == null) throw notFound();
            throw conflict("SCENARIO_NOT_APPROVED", "승인된 시나리오만 새 판으로 고칠 수 있습니다.");
        }
        if (request.version() != source.getScenarioVersion()) {
            throw versionMismatch();
        }
        UUID activityId = waitingActivity(goalId);
        var preparation = preparations.prepare(activityId, authentication);
        boolean candidate =
                scenarios
                        .findReuseCandidates(
                                preparation.reuseKey().goalContentHash(),
                                preparation.reuseKey().scenarioLevel(),
                                preparation.initialDifficulty().childId())
                        .contains(sourceId);
        if (!candidate) {
            throw conflict("SCENARIO_NOT_REUSABLE", "이 활동에 쓸 수 있는 시나리오가 아닙니다.");
        }
        var existing = scenarios.findDraftForGoal(goalId);
        if (existing.isPresent()) {
            if (sourceId.equals(existing.get().getSourceScenarioId())) {
                return new Created(view(existing.get()), false);
            }
            throw draftExists();
        }

        Instant now = clock.instant();
        Scenario draft = Scenario.editedFrom(source, UUID.randomUUID(), goalId, now);
        Map<UUID, UUID> copied = new HashMap<>();
        try {
            scenarios.saveAndFlush(draft);
            for (ScenarioMicroGoal goalOfSource :
                    microGoals.findByScenarioIdOrderByOrderNo(sourceId)) {
                UUID newId = UUID.randomUUID();
                copied.put(goalOfSource.getMicroGoalId(), newId);
                microGoals.save(goalOfSource.copyTo(draft.getScenarioId(), newId));
            }
            microGoals.flush();
        } catch (DataIntegrityViolationException raced) {
            // 같은 목표로 동시에 만든 초안이 먼저 저장됐다.
            throw draftExists();
        }
        copyOpeningFocus(sourceId, draft.getScenarioId(), copied);
        applyEdits(
                draft,
                request.title(),
                request.summary(),
                remap(request.microGoals(), copied),
                goal);
        return new Created(view(draft), true);
    }

    @Transactional(readOnly = true)
    public ScenarioResponse get(UUID scenarioId, Authentication authentication) {
        Scenario scenario = scenarios.findById(scenarioId).orElseThrow(this::notFound);
        if (scenario.getGoalId() == null) {
            // 공용 승인본은 어느 강사나 본다. 목표에 매이지 않은 옛 초안은 주인이 없어 보이지 않는다.
            if (scenario.getStatus() != ScenarioStatus.APPROVED) throw notFound();
            return ScenarioResponse.of(
                    scenario, microGoals.findByScenarioIdOrderByOrderNo(scenarioId), null, null);
        }
        ownedGoal(scenario, authentication);
        return view(scenario);
    }

    /** 초안의 칸을 고치고, status 가 있으면 판정한다. */
    @Transactional
    public ScenarioResponse review(
            UUID scenarioId, ReviewScenarioRequest request, Authentication authentication) {
        Scenario scenario = scenarios.findById(scenarioId).orElseThrow(this::notFound);
        LearningGoalResponse goal = ownedGoal(scenario, authentication);
        if (!scenario.isDraft()) {
            throw conflict("SCENARIO_NOT_EDITABLE", "판정이 끝난 시나리오는 고칠 수 없습니다.");
        }
        if (request.version() != scenario.getScenarioVersion()) {
            throw versionMismatch();
        }
        // 화면이 읽은 뒤 다른 곳에서 저장했으면, 이 화면은 그 내용을 보지 못했다. 덮어쓰거나 보지 못한 내용을 승인하지 않는다.
        if (!request.editRevision().equals(scenario.getRowVersion())) {
            throw editConflict();
        }
        String status = request.status();
        if (status != null
                && !List.of("DRAFT", "APPROVED", "REJECTED", "REVISION_REQUIRED")
                        .contains(status)) {
            throw invalid("알 수 없는 검토 상태입니다.");
        }
        if ("REVISION_REQUIRED".equals(status)) {
            throw conflict(
                    "SCENARIO_REGENERATION_UNAVAILABLE", "다시 만들기는 아직 쓸 수 없습니다. 칸을 직접 고쳐 저장해 주세요.");
        }
        // 승인할 때는 초안을 고쳐 저장하기 전에 활동·아동부터 잠근다. 아동 삭제가 활동 → 아동 → 초안(목표 CASCADE) 순서로 잠그므로,
        // 초안을 먼저 잡으면 두 요청이 서로를 기다리다 멈출 수 있다.
        UUID approvingActivity =
                "APPROVED".equals(status) ? lockWaitingActivity(goal.goalId()) : null;
        try {
            applyEdits(scenario, request.title(), request.summary(), request.microGoals(), goal);
            if (approvingActivity != null) {
                approve(scenario, approvingActivity);
                // 승인하면 공용이 되어 목표에서 떨어지지만, 응답은 이 목표의 활동에 붙은 결과로 보여 준다.
                return view(
                        scenarios.findById(scenarioId).orElseThrow(this::notFound), goal.goalId());
            } else if ("REJECTED".equals(status)) {
                scenario.reject();
                scenarios.saveAndFlush(scenario);
            }
        } catch (OptimisticLockingFailureException | OptimisticLockException raced) {
            // 같은 수정 번호를 읽은 요청이 동시에 들어와 다른 쪽이 먼저 저장했다. 저장소를 거친 갱신은 Spring 예외로, 직접 올린 수정 번호는 JPA
            // 예외로 온다.
            throw editConflict();
        }
        return view(scenario);
    }

    /** 초안을 지운다([폐기]). 판정이 끝난 판은 기록이라 지우지 않는다. */
    @Transactional
    public void delete(UUID scenarioId, Authentication authentication) {
        Scenario scenario = scenarios.findById(scenarioId).orElseThrow(this::notFound);
        ownedGoal(scenario, authentication);
        if (!scenario.isDraft()) {
            throw conflict("SCENARIO_IN_USE", "판정이 끝난 시나리오는 지울 수 없습니다.");
        }
        scenarios.delete(scenario);
        scenarios.flush();
    }

    /** 이 목표로 시나리오를 기다리는 활동을 잠근다. 퀴즈 저장·삭제와 같은 활동 → 아동 잠금 순서를 지킨다. */
    private UUID lockWaitingActivity(UUID goalId) {
        UUID activityId = waitingActivity(goalId);
        var activity = activities.findByIdForUpdate(activityId).orElseThrow(this::notFound);
        children.findByIdForUpdate(activity.getChildId()).orElseThrow(this::notFound);
        if (activity.getScenarioId() != null) {
            throw conflict("SCENARIO_ALREADY_ATTACHED", "이미 시나리오가 정해진 활동입니다.");
        }
        return activityId;
    }

    private void approve(Scenario scenario, UUID activityId) {
        List<ScenarioMicroGoal> goalsOfDraft =
                microGoals.findByScenarioIdOrderByOrderNo(scenario.getScenarioId());
        if (goalsOfDraft.isEmpty()
                || goalsOfDraft.stream().anyMatch(g -> g.getRequiredEvidence().isEmpty())) {
            throw invalid("작은 목표와 필요 증거가 있어야 승인할 수 있습니다.");
        }
        scenario.approve(clock.instant());
        scenarios.saveAndFlush(scenario);
        if (activities.attachScenarioIfEmpty(activityId, scenario.getScenarioId()) != 1) {
            throw conflict("SCENARIO_ALREADY_ATTACHED", "이미 시나리오가 정해진 활동입니다.");
        }
    }

    private void applyEdits(
            Scenario scenario,
            String title,
            String summary,
            List<DraftMicroGoalPatch> patches,
            LearningGoalResponse goal) {
        requireText(title, "title", goal);
        requireText(summary, "summary", goal);
        List<MicroGoalEdit> goalEdits = microGoalEdits(scenario, patches, goal);

        // 시나리오 행을 먼저 고치고 작은 목표를 고친다. 아동 삭제의 CASCADE 와 같은 순서다.
        long revisionBefore = scenario.getRowVersion();
        scenario.edit(title == null ? null : title.trim(), summary == null ? null : summary.trim());
        scenarios.saveAndFlush(scenario);
        // 제목·요약이 바뀌었으면 위 저장에서 수정 번호가 이미 올랐다. 작은 목표만 바뀌면 시나리오 행은 그대로라 직접 올린다.
        boolean microGoalsChanged = goalEdits.stream().anyMatch(MicroGoalEdit::changes);
        if (microGoalsChanged && scenario.getRowVersion() == revisionBefore) {
            entityManager.lock(scenario, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
        for (MicroGoalEdit edit : goalEdits) {
            edit.target().edit(edit.description(), edit.requiredEvidence());
        }
        microGoals.flush();
    }

    private record MicroGoalEdit(
            ScenarioMicroGoal target, String description, List<String> requiredEvidence) {
        boolean changes() {
            return !description.equals(target.getDescription())
                    || !requiredEvidence.equals(target.getRequiredEvidence());
        }
    }

    /** 작은 목표 수정 요청을 검사해 고칠 내용으로 바꾼다. 아직 고치지 않는다. */
    private List<MicroGoalEdit> microGoalEdits(
            Scenario scenario, List<DraftMicroGoalPatch> patches, LearningGoalResponse goal) {
        if (patches == null || patches.isEmpty()) return List.of();
        Map<UUID, ScenarioMicroGoal> current =
                microGoals.findByScenarioIdOrderByOrderNo(scenario.getScenarioId()).stream()
                        .collect(
                                Collectors.toMap(
                                        ScenarioMicroGoal::getMicroGoalId, Function.identity()));
        if (patches.stream().map(DraftMicroGoalPatch::microGoalId).distinct().count()
                != patches.size()) {
            throw invalid("같은 작은 목표를 두 번 고칠 수 없습니다.");
        }
        return patches.stream()
                .map(
                        patch -> {
                            ScenarioMicroGoal target = current.get(patch.microGoalId());
                            if (target == null) {
                                throw invalid("이 시나리오의 작은 목표만 고칠 수 있습니다.");
                            }
                            requireText(patch.description(), "microGoals.description", goal);
                            for (String evidence : patch.requiredEvidence()) {
                                requireText(evidence, "microGoals.requiredEvidence", goal);
                            }
                            return new MicroGoalEdit(
                                    target,
                                    patch.description().trim(),
                                    patch.requiredEvidence().stream().map(String::trim).toList());
                        })
                .toList();
    }

    /** 강사가 쓴 문장은 생성 결과 검사를 거치지 않으므로 빈 값·보이지 않는 문자·목표의 피할 표현을 여기서 막는다. */
    private void requireText(String value, String field, LearningGoalResponse goal) {
        if (value == null) return;
        if (!TextRules.hasVisibleCharacter(value) || TextRules.containsUnusableCharacter(value)) {
            throw invalid(field + " 를 확인해 주세요.");
        }
        String text = withoutSpacing(value);
        for (String forbidden : goal.forbiddenExpressions()) {
            String needle = withoutSpacing(forbidden);
            if (!needle.isEmpty() && text.contains(needle)) {
                throw invalid(field + " 에 목표의 피할 표현이 들어 있습니다.");
            }
        }
    }

    /**
     * 피할 표현을 찾기 전에 양쪽에서 띄어쓰기를 지운다. 생성 결과 검사도 공백을 지우고 찾으므로 같은 기준이다. "바 보"처럼 띄어 써도, 피할 표현을 "못 해"로 적고
     * 문장에 "못해"라고 써도 걸린다. 일반 공백 말고도 NBSP 같은 공백류와 폭 없는 공백 같은 서식 문자를 함께 지워, 눈에 안 보이는 문자를 끼워 넣어 피하지 못하게
     * 한다.
     */
    private static String withoutSpacing(String value) {
        StringBuilder kept = new StringBuilder(value.length());
        value.codePoints()
                .filter(
                        cp ->
                                !Character.isWhitespace(cp)
                                        && !Character.isSpaceChar(cp)
                                        && Character.getType(cp) != Character.FORMAT)
                .forEach(kept::appendCodePoint);
        return kept.toString().toLowerCase(Locale.ROOT);
    }

    private List<DraftMicroGoalPatch> remap(
            List<DraftMicroGoalPatch> patches, Map<UUID, UUID> copied) {
        if (patches == null) return null;
        // 새 판 만들기 요청은 원본의 작은 목표 ID 로 고칠 곳을 가리킨다.
        return patches.stream()
                .map(
                        p -> {
                            UUID mapped = copied.get(p.microGoalId());
                            if (mapped == null) {
                                throw invalid("원본 시나리오의 작은 목표만 고칠 수 있습니다.");
                            }
                            return new DraftMicroGoalPatch(
                                    mapped, p.description(), p.requiredEvidence());
                        })
                .toList();
    }

    private void copyOpeningFocus(UUID sourceId, UUID draftId, Map<UUID, UUID> copied) {
        for (Map<String, Object> row :
                jdbc.queryForList(
                        "select support_level, micro_goal_id from scenario_opening_focus where scenario_id = ?",
                        sourceId)) {
            UUID mapped = copied.get((UUID) row.get("micro_goal_id"));
            if (mapped != null) {
                jdbc.update(
                        "insert into scenario_opening_focus(scenario_id, support_level, micro_goal_id) values(?, ?, ?)",
                        draftId,
                        row.get("support_level"),
                        mapped);
            }
        }
    }

    private UUID waitingActivity(UUID goalId) {
        List<UUID> waiting = activities.findWaitingFirstCombinedByGoal(goalId);
        if (waiting.size() != 1) {
            throw conflict("SCENARIO_NO_WAITING_ACTIVITY", "이 목표로 시나리오를 기다리는 활동이 없습니다.");
        }
        return waiting.getFirst();
    }

    private LearningGoalResponse ownedGoal(Scenario scenario, Authentication authentication) {
        if (scenario.getGoalId() == null) throw notFound();
        try {
            return goals.get(scenario.getGoalId(), authentication);
        } catch (ResourceNotFoundException hidden) {
            // 남의 초안은 없는 시나리오와 같게 숨긴다.
            throw notFound();
        }
    }

    private ScenarioResponse view(Scenario scenario) {
        return view(scenario, scenario.getGoalId());
    }

    private ScenarioResponse view(Scenario scenario, UUID goalId) {
        return ScenarioResponse.of(
                scenario,
                microGoals.findByScenarioIdOrderByOrderNo(scenario.getScenarioId()),
                goalId,
                ScenarioResponse.NEWLY_GENERATED);
    }

    private ApiException versionMismatch() {
        return conflict("SCENARIO_VERSION_MISMATCH", "다른 판의 시나리오입니다. 새로 불러와 주세요.");
    }

    private ApiException editConflict() {
        return conflict("VERSION_CONFLICT", "다른 곳에서 먼저 고쳤습니다. 새로 불러와 주세요.");
    }

    private ApiException draftExists() {
        return conflict("SCENARIO_DRAFT_EXISTS", "이 목표로 고치는 중인 시나리오가 이미 있습니다.");
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", message);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("SCENARIO_NOT_FOUND", "시나리오를 찾을 수 없습니다.");
    }
}
