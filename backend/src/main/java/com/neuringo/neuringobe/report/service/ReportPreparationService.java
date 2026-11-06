package com.neuringo.neuringobe.report.service;

import com.neuringo.neuringobe.activity.domain.Activity;
import com.neuringo.neuringobe.activity.domain.ActivityType;
import com.neuringo.neuringobe.activity.repository.ActivityRepository;
import com.neuringo.neuringobe.ai.application.prompt.LearningReportPromptFactory;
import com.neuringo.neuringobe.goal.repository.LearningGoalRepository;
import com.neuringo.neuringobe.quiz.repository.QuizResultRepository;
import com.neuringo.neuringobe.report.application.LearningReportInputAssembler;
import com.neuringo.neuringobe.report.application.RecommendationDrafts;
import com.neuringo.neuringobe.report.application.ReportSnapshots;
import com.neuringo.neuringobe.report.application.RoleplayAnswerMapper;
import com.neuringo.neuringobe.report.config.ReportProperties;
import com.neuringo.neuringobe.report.domain.DifficultyBasis;
import com.neuringo.neuringobe.report.domain.LearningMetrics;
import com.neuringo.neuringobe.report.domain.QuizEvidence;
import com.neuringo.neuringobe.report.domain.RecommendationPolicy;
import com.neuringo.neuringobe.report.domain.ReportEvidence;
import com.neuringo.neuringobe.report.domain.roleplay.RoleplayGoalSummarizer;
import com.neuringo.neuringobe.report.repository.LearningReportStore;
import com.neuringo.neuringobe.report.repository.RecommendationStore;
import com.neuringo.neuringobe.report.repository.RoleplayAnswerReader;
import com.neuringo.neuringobe.scenario.service.FollowUpDifficultyService;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 역할극이 끝난 활동의 리포트 1단계. LLM 을 부르지 않고 DB 기록만으로 목표별 결과·지표·다음 난이도·추천을 계산해 한 트랜잭션에 남긴다.
 *
 * <p>순서는 답 기록 읽기, 목표별 결과로 요약(RoleplayGoalSummarizer), 다음 난이도 결정(FollowUpDifficultyService), 지표와 생성
 * 루프 입력 만들기, 추천 만들기다. 리포트는 PENDING 으로 남고 AI 요약은 생성 작업이 나중에 채운다. 그래서 LLM 이 실패해도 숫자·난이도·추천은 이미 저장돼
 * 있다.
 *
 * <p>같은 활동을 다시 받으면 아무것도 하지 않는다(learning_report.activity_id UNIQUE 가 마지막 장치).
 */
@Service
public class ReportPreparationService {
    private static final Logger log = LoggerFactory.getLogger(ReportPreparationService.class);

    private final ActivityRepository activities;
    private final LearningGoalRepository goals;
    private final QuizResultRepository quizzes;
    private final RoleplayAnswerReader answers;
    private final LearningReportStore reports;
    private final RecommendationStore recommendations;
    private final FollowUpDifficultyService difficulties;
    private final RoleplayAnswerMapper mapper;
    private final RoleplayGoalSummarizer summarizer;
    private final LearningReportInputAssembler assembler;
    private final RecommendationPolicy policy;
    private final ReportProperties properties;
    private final JsonMapper json;
    private final Clock clock;

    public ReportPreparationService(
            ActivityRepository activities,
            LearningGoalRepository goals,
            QuizResultRepository quizzes,
            RoleplayAnswerReader answers,
            LearningReportStore reports,
            RecommendationStore recommendations,
            FollowUpDifficultyService difficulties,
            RoleplayAnswerMapper mapper,
            RoleplayGoalSummarizer summarizer,
            LearningReportInputAssembler assembler,
            RecommendationPolicy policy,
            ReportProperties properties,
            JsonMapper json,
            Clock clock) {
        this.activities = activities;
        this.goals = goals;
        this.quizzes = quizzes;
        this.answers = answers;
        this.reports = reports;
        this.recommendations = recommendations;
        this.difficulties = difficulties;
        this.mapper = mapper;
        this.summarizer = summarizer;
        this.assembler = assembler;
        this.policy = policy;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @return 새로 만든 PENDING 리포트 ID. 이미 있거나 만들 수 없으면 비어 있다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<UUID> prepare(UUID activityId) {
        if (reports.existsByActivity(activityId)) {
            return Optional.empty();
        }
        Activity activity = activities.findById(activityId).orElse(null);
        if (activity == null
                || (activity.getActivityType() != ActivityType.FIRST_COMBINED
                        && activity.getActivityType() != ActivityType.ROLEPLAY)) {
            return Optional.empty();
        }
        var rows = answers.readCompleted(activityId).orElse(null);
        if (rows == null || rows.goals().isEmpty()) {
            log.warn("리포트를 준비할 완료 역할극 기록이 없습니다: activityId={}", activityId);
            return Optional.empty();
        }
        var roleplay = mapper.map(rows);
        if (roleplay.unmatchedObservations() > 0) {
            log.warn(
                    "승인 기준과 맞지 않는 관찰을 뺐습니다: activityId={} count={}",
                    activityId,
                    roleplay.unmatchedObservations());
        }
        var summary =
                summarizer.summarize(roleplay.goals(), roleplay.turns(), roleplay.startSupport());
        var decision =
                difficulties.decideAfterRoleplay(
                        activityId, activity.getChildId(), roleplay.level(), summary.outcomes());
        QuizEvidence quiz =
                activity.getActivityType() == ActivityType.FIRST_COMBINED
                        ? quizzes.findById(activityId)
                                .map(
                                        r ->
                                                new QuizEvidence(
                                                        r.getValidQuestionCount(),
                                                        r.getCorrectQuestionCount(),
                                                        r.getResolvedAfterHintCount()))
                                .orElse(null)
                        : null;
        var evidence =
                new ReportEvidence(
                        quiz,
                        summary.goals(),
                        summary.technicalExclusions(),
                        new DifficultyBasis(
                                DifficultyBasis.DecisionType.FOLLOW_UP,
                                decision.getNextLevel(),
                                decision.getReason().name(),
                                decision.getPolicyVersion()));
        var metrics = LearningMetrics.from(evidence);
        var snapshot = ReportSnapshots.build(quiz, roleplay, summary, metrics, decision);

        UUID reportId = UUID.randomUUID();
        var now = clock.instant();
        reports.insertPending(
                reportId,
                rows.recordId(),
                activityId,
                activity.getChildId(),
                json.writeValueAsString(snapshot),
                json.writeValueAsString(assembler.assemble(evidence, metrics)),
                LearningReportPromptFactory.GENERATION_PROMPT_VERSION
                        + "+"
                        + LearningReportPromptFactory.REVIEW_PROMPT_VERSION,
                now);

        var goal =
                activity.getGoalId() == null
                        ? null
                        : goals.findById(activity.getGoalId()).orElse(null);
        var current =
                new RecommendationDrafts.CurrentGoal(
                        activity.getGoalId(),
                        goal == null ? null : goal.getTitle(),
                        goal == null ? null : goal.getCategory());
        var drafts =
                RecommendationDrafts.from(
                        policy.recommend(summary.goals()),
                        current,
                        roleplay.level(),
                        properties.extendFromLowestLevel());
        for (var draft : drafts) {
            recommendations.insert(
                    new RecommendationStore.NewRecommendation(
                            UUID.randomUUID(),
                            reportId,
                            rows.recordId(),
                            activityId,
                            activity.getChildId(),
                            draft.type().name(),
                            draft.displayOrder(),
                            draft.reasonCode(),
                            draft.reason(),
                            draft.basisGoalTitle(),
                            json.writeValueAsString(draft.evidence()),
                            draft.sufficiency().name(),
                            json.writeValueAsString(draft.suggestedGoal()),
                            policy.version(),
                            now));
        }
        return Optional.of(reportId);
    }
}
