package com.neuringo.neuringobe.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AiOperation;
import com.neuringo.neuringobe.ai.eval.ReportEvalReport.Price;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 키 없이 실행기·회귀 세트·보고서가 깨지지 않았는지 본다. 품질은 재지 않는다. */
class ReportEvalRunnerTest {

    @Test
    void runsEveryCaseWithTheScriptedModelAndRendersTheReport() {
        ReportRegressionSet set = ReportRegressionSet.load(ReportRegressionSet.DEFAULT_RESOURCE);
        ReportEvalRunner runner =
                new ReportEvalRunner(set, new ScriptedReportLlm(), JsonMapper.builder().build());

        List<ReportRecord> records = runner.run(1, testCase -> true, record -> {});

        assertThat(records).hasSize(set.cases().size());
        assertThat(records).allSatisfy(record -> assertThat(record.displayable()).isTrue());
        assertThat(records).allSatisfy(record -> assertThat(record.formatFailures()).isZero());
        assertThat(records).allSatisfy(record -> assertThat(record.calls()).hasSize(2));
        // 가짜 모델은 효과적 지원을 비워 두므로 R1 기대값 위반으로 잡혀야 한다.
        assertThat(records.getFirst().checkFailures()).contains("EMPTY:effective_supports");

        String report =
                ReportEvalReport.render(
                        new ReportEvalReport.Meta(
                                "smoke", "now", null, set.setId(), "fake", "fake", 1),
                        records,
                        Map.of(
                                AiOperation.REPORT_GENERATION, new Price(0, 0),
                                AiOperation.REPORT_REVIEW, new Price(0, 0)),
                        List.of(),
                        null,
                        null,
                        null);
        assertThat(report).contains("## 요약", "## 사례별 결과", "### R7");
    }
}
