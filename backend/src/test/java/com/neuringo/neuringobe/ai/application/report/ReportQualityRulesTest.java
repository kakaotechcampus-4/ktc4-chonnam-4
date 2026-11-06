package com.neuringo.neuringobe.ai.application.report;

import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.draft;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.goodDraft;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.input;
import static com.neuringo.neuringobe.ai.application.report.ReportTestFixtures.item;
import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.prompt.LearningReportInput;
import com.neuringo.neuringobe.ai.application.prompt.ReportCodes;
import com.neuringo.neuringobe.ai.application.prompt.ReportLimits;
import com.neuringo.neuringobe.ai.application.structured.output.ReportDraft;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 평가 모델 전에 코드로 막는 리포트 규칙(03 VS-014 주요 예외·인수 조건). */
class ReportQualityRulesTest {

    private final List<ReportQualityRule> rules = ReportQualityRules.defaults(ReportLimits.DEFAULT);

    @Test
    void goodDraftPassesEveryRule() {
        assertThat(issues(goodDraft())).isEmpty();
    }

    @Test
    void everyItemNeedsKnownEvidence() {
        ReportDraft draft =
                draft(
                        "요약이에요.",
                        List.of(item("근거 없는 문장이에요")),
                        List.of(item("없는 근거예요", "E9", "E1")),
                        List.of());

        assertThat(new EvidenceReferenceRule().check(draft, input()))
                .extracting(ReportIssue::code)
                .containsExactly(ReportCodes.MISSING_EVIDENCE, ReportCodes.UNKNOWN_EVIDENCE);
        assertThat(new EvidenceReferenceRule().check(draft, input()).get(1).detail())
                .contains("E9");
    }

    @Test
    void technicalEvidenceOrWordsAreNotDifficulties() {
        ReportDraft citesTechnical =
                draft("요약이에요.", List.of(), List.of(), List.of(item("대답이 끊겼어요", "E4")));
        ReportDraft mentionsTechnical =
                draft("요약이에요.", List.of(), List.of(), List.of(item("음성 인식이 어려웠어요", "E3")));
        ReportDraft technicalAsObservation =
                draft("요약이에요.", List.of(item("한 번은 다시 말했어요", "E4")), List.of(), List.of());

        TechnicalSeparationRule rule = new TechnicalSeparationRule();
        assertThat(rule.check(citesTechnical, input()))
                .extracting(ReportIssue::code)
                .containsExactly(ReportCodes.TECHNICAL_ERROR_AS_DIFFICULTY);
        assertThat(rule.check(mentionsTechnical, input())).hasSize(1);
        assertThat(rule.check(technicalAsObservation, input())).isEmpty();
    }

    @Test
    void diagnosticWordsAreBlockedInSummaryAndItems() {
        ReportDraft draft =
                draft("ADHD 성향이 보여요.", List.of(item("산만해서 집중하지 못했어요", "E1")), List.of(), List.of());

        List<ReportIssue> issues = new DiagnosticLanguageRule().check(draft, input());

        assertThat(issues)
                .extracting(ReportIssue::code)
                .containsExactly(ReportCodes.DIAGNOSTIC_LANGUAGE);
        assertThat(issues.getFirst().detail()).contains("adhd", "성향", "산만");
    }

    @Test
    void difficultiesAndSupportsMustComeFromGoalsThatNeededThem() {
        SectionPlacementRule rule = new SectionPlacementRule();
        LearningReportInput input = ReportTestFixtures.placementInput();

        assertThat(
                        rule.check(
                                draft(
                                        "요약",
                                        List.of(),
                                        List.of(),
                                        List.of(item("어려웠어요", "E1", "E2"))),
                                input))
                .extracting(ReportIssue::code)
                .containsExactly(ReportCodes.MISPLACED_ITEM);
        assertThat(
                        rule.check(
                                draft(
                                        "요약",
                                        List.of(),
                                        List.of(item("열린 질문이 도움이 됐어요", "E1")),
                                        List.of()),
                                input))
                .extracting(ReportIssue::code)
                .containsExactly(ReportCodes.MISPLACED_ITEM);

        assertThat(
                        rule.check(
                                draft(
                                        "요약",
                                        List.of(),
                                        List.of(),
                                        List.of(item("두 선택지까지 받았어요", "E3"))),
                                input))
                .isEmpty();
        assertThat(
                        rule.check(
                                draft(
                                        "요약",
                                        List.of(),
                                        List.of(item("단서가 도움이 됐어요", "E2")),
                                        List.of()),
                                input))
                .isEmpty();
        // 목표와 상관없는 근거(퀴즈)를 함께 달면 판단하지 않는다.
        assertThat(
                        rule.check(
                                draft(
                                        "요약",
                                        List.of(),
                                        List.of(),
                                        List.of(item("첫 응답이 틀렸어요", "E1", "E4"))),
                                input))
                .isEmpty();
    }

    @Test
    void lengthLimitsAreInclusive() {
        ReportLimits limits = new ReportLimits(5, 4, 1);
        LengthRule rule = new LengthRule(limits);

        assertThat(
                        rule.check(
                                draft("다섯글자요", List.of(item("네글자요", "E1")), List.of(), List.of()),
                                input()))
                .isEmpty();
        assertThat(rule.check(draft("여섯글자예요", List.of(), List.of(), List.of()), input()))
                .extracting(ReportIssue::code)
                .containsExactly(ReportCodes.TOO_LONG);
        assertThat(
                        rule.check(
                                draft(
                                        "요약",
                                        List.of(item("하나", "E1"), item("둘", "E1")),
                                        List.of(),
                                        List.of()),
                                input()))
                .hasSize(1);
    }

    private List<ReportIssue> issues(ReportDraft draft) {
        return rules.stream().flatMap(rule -> rule.check(draft, input()).stream()).toList();
    }
}
