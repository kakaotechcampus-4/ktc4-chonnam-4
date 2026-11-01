package com.neuringo.neuringobe.scenario.service;

import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.common.ApiException;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import com.neuringo.neuringobe.scenario.dto.InitialScenarioPreparation;
import com.neuringo.neuringobe.scenario.dto.ScenarioCandidateResponse;
import com.neuringo.neuringobe.scenario.dto.ScenarioResponse;
import com.neuringo.neuringobe.scenario.repository.ScenarioMicroGoalRepository;
import com.neuringo.neuringobe.scenario.repository.ScenarioRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최초 결합 활동의 재사용 후보를 담당 강사에게 하나씩 보여 주고, 강사가 승인한 후보를 활동에 붙인다.
 *
 * <p>후보 순서와 조건은 {@link ScenarioRepository#findReuseCandidates}가 정한다. 넘겨 본 후보는 저장하지 않는다. 담당 강사인지,
 * 퀴즈로 난이도가 정해졌는지, 목표 내용이 있는지는 {@link InitialScenarioPreparationService}가 확인한다.
 */
@Service
public class ScenarioReuseService {
    private final InitialScenarioPreparationService preparations;
    private final ActivityRepository activities;
    private final ChildRepository children;
    private final ScenarioRepository scenarios;
    private final ScenarioMicroGoalRepository microGoals;

    public ScenarioReuseService(
            InitialScenarioPreparationService preparations,
            ActivityRepository activities,
            ChildRepository children,
            ScenarioRepository scenarios,
            ScenarioMicroGoalRepository microGoals) {
        this.preparations = preparations;
        this.activities = activities;
        this.children = children;
        this.scenarios = scenarios;
        this.microGoals = microGoals;
    }

    /**
     * {@code after} 다음 후보를 준다. {@code after}가 없거나 그사이 후보에서 빠졌으면 첫 후보부터 준다. 순서는 요청할 때마다 다시 계산하므로,
     * 넘기는 사이에 다른 아동이 쓴 후보는 드물게 건너뛰거나 다시 보일 수 있다.
     */
    @Transactional(readOnly = true)
    public ScenarioCandidateResponse candidate(
            UUID activityId, UUID after, Authentication authentication) {
        var preparation = preparations.prepare(activityId, authentication);
        requireWaiting(activityId);
        List<UUID> candidates = candidatesFor(preparation);
        int index = after == null ? 0 : candidates.indexOf(after) + 1;
        if (index >= candidates.size()) {
            return new ScenarioCandidateResponse(null, false);
        }
        return new ScenarioCandidateResponse(
                view(candidates.get(index)), index + 1 < candidates.size());
    }

    /** 담당 강사가 승인한 후보를 붙인다. 같은 후보로 다시 요청하면 같은 결과를 준다. */
    @Transactional
    public ScenarioResponse attach(
            UUID activityId, UUID scenarioId, Authentication authentication) {
        var preparation = preparations.prepare(activityId, authentication);
        // 퀴즈 저장·삭제와 같은 활동 → 아동 잠금 순서를 지킨다.
        var activity = activities.findByIdForUpdate(activityId).orElseThrow(this::notFound);
        children.findByIdForUpdate(activity.getChildId()).orElseThrow(this::notFound);
        if (activity.getScenarioId() != null) {
            if (activity.getScenarioId().equals(scenarioId)) {
                return view(scenarioId);
            }
            throw alreadyAttached();
        }
        // 화면을 띄운 뒤 그 아동이 같은 시나리오를 받았거나 조건이 바뀌었으면 붙이지 않는다.
        if (!candidatesFor(preparation).contains(scenarioId)) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "SCENARIO_NOT_REUSABLE", "이 활동에 붙일 수 있는 시나리오가 아닙니다.");
        }
        if (activities.attachScenarioIfEmpty(activityId, scenarioId) != 1) {
            throw alreadyAttached();
        }
        return view(scenarioId);
    }

    private List<UUID> candidatesFor(InitialScenarioPreparation preparation) {
        return scenarios.findReuseCandidates(
                preparation.reuseKey().goalContentHash(),
                preparation.reuseKey().scenarioLevel(),
                preparation.initialDifficulty().childId());
    }

    private void requireWaiting(UUID activityId) {
        var activity = activities.findById(activityId).orElseThrow(this::notFound);
        if (activity.getScenarioId() != null) {
            throw alreadyAttached();
        }
    }

    private ScenarioResponse view(UUID scenarioId) {
        var scenario = scenarios.findById(scenarioId).orElseThrow(this::notFound);
        return ScenarioResponse.of(scenario, microGoals.findByScenarioIdOrderByOrderNo(scenarioId));
    }

    private ApiException alreadyAttached() {
        return new ApiException(
                HttpStatus.CONFLICT, "SCENARIO_ALREADY_ATTACHED", "이미 시나리오가 정해진 활동입니다.");
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("ACTIVITY_NOT_FOUND", "활동을 찾을 수 없습니다.");
    }
}
