package com.neuringo.neuringobe.scenario.repository;

import com.neuringo.neuringobe.scenario.domain.ScenarioMicroGoal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioMicroGoalRepository extends JpaRepository<ScenarioMicroGoal, UUID> {
    List<ScenarioMicroGoal> findByScenarioIdOrderByOrderNo(UUID scenarioId);
}
