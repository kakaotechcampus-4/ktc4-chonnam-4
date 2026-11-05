package com.neuringo.neuringobe.assignment.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neuringo.neuringobe.activity.repository.ActivityReadinessRepository;
import com.neuringo.neuringobe.child.security.ChildAccessScope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ActivityReadinessQueryServiceTest {
    @Test
    void databaseFailureIsNotReportedAsAnActivityPreparationProblem() {
        var repository = mock(ActivityReadinessRepository.class);
        var failure = new DataAccessResourceFailureException("synthetic failure");
        when(repository.load(any(), any(), any())).thenThrow(failure);
        var service = new ActivityReadinessQueryService(mock(ChildAccessScope.class), repository);
        assertThatThrownBy(() -> service.read(UUID.randomUUID(), List.of(UUID.randomUUID()), null))
                .isSameAs(failure);
    }
}
