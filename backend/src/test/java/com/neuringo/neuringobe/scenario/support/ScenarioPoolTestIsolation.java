package com.neuringo.neuringobe.scenario.support;

import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.extension.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** 순차 실행 검사에서 이번 검사가 추가한 공통 승인 자료만 정리한다. */
public class ScenarioPoolTestIsolation implements BeforeEachCallback, AfterEachCallback {
    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ScenarioPoolTestIsolation.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE)
                .put(
                        "baseline",
                        new HashSet<>(
                                jdbc(context)
                                        .queryForList(
                                                "SELECT scenario_id FROM scenario_approval",
                                                UUID.class)));
    }

    @Override
    public void afterEach(ExtensionContext context) {
        var baseline = context.getStore(NAMESPACE).get("baseline", java.util.Set.class);
        if (baseline == null) return;
        var jdbc = jdbc(context);
        for (var id : jdbc.queryForList("SELECT scenario_id FROM scenario_approval", UUID.class)) {
            if (baseline.contains(id)) continue;
            jdbc.update("DELETE FROM activity_scenario_approval WHERE scenario_id=?", id);
            jdbc.update("DELETE FROM scenario_approval WHERE scenario_id=?", id);
        }
    }

    private JdbcTemplate jdbc(ExtensionContext context) {
        return SpringExtension.getApplicationContext(context).getBean(JdbcTemplate.class);
    }
}
