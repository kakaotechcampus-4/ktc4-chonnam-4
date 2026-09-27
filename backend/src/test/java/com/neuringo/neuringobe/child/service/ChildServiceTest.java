package com.neuringo.neuringobe.child.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.neuringo.neuringobe.child.domain.Child;
import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.child.dto.ChildResponse;
import com.neuringo.neuringobe.child.dto.CreateChildRequest;
import com.neuringo.neuringobe.child.repository.ChildRepository;
import com.neuringo.neuringobe.classroom.service.ClassroomService;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ChildServiceTest {

    @Mock private ChildRepository childRepository;

    @Mock private ClassroomService classroomService;

    @InjectMocks private ChildService childService;

    @Test
    void doesNotSaveChildWhenClassroomIsMissing() {
        UUID classId = UUID.randomUUID();
        willThrow(new ResourceNotFoundException("CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다"))
                .given(classroomService)
                .validateClassroomExists(classId);

        assertThatThrownBy(() -> childService.create(classId, new CreateChildRequest("테스트아동")))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(childRepository, never()).save(any());
    }

    @Test
    void savesNewChildAsActiveInGivenClassroom() {
        UUID classId = UUID.randomUUID();
        given(childRepository.save(any(Child.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ChildResponse response = childService.create(classId, new CreateChildRequest("테스트아동"));

        ArgumentCaptor<Child> saved = ArgumentCaptor.forClass(Child.class);
        verify(childRepository).save(saved.capture());
        Child child = saved.getValue();
        assertThat(child.getChildId()).isNotNull();
        assertThat(child.getClassId()).isEqualTo(classId);
        assertThat(child.getDisplayName()).isEqualTo("테스트아동");
        assertThat(child.getStatus()).isEqualTo(ChildStatus.ACTIVE);

        assertThat(response.childId()).isEqualTo(child.getChildId());
        assertThat(response.classId()).isEqualTo(classId);
        assertThat(response.displayName()).isEqualTo("테스트아동");
        assertThat(response.status()).isEqualTo(ChildStatus.ACTIVE);
    }

    @Test
    void doesNotQueryChildrenWhenClassroomIsMissing() {
        UUID classId = UUID.randomUUID();
        willThrow(new ResourceNotFoundException("CLASSROOM_NOT_FOUND", "학급을 찾을 수 없습니다"))
                .given(classroomService)
                .validateClassroomExists(classId);

        assertThatThrownBy(() -> childService.list(classId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(childRepository, never()).findByClassId(any());
    }

    @Test
    void returnsChildrenOfExistingClassroomAsResponses() {
        UUID classId = UUID.randomUUID();
        Child active = new Child(UUID.randomUUID(), classId, "테스트아동1", ChildStatus.ACTIVE);
        Child paused = new Child(UUID.randomUUID(), classId, "테스트아동2", ChildStatus.PAUSED);
        given(childRepository.findByClassId(classId)).willReturn(List.of(active, paused));

        List<ChildResponse> responses = childService.list(classId);

        assertThat(responses)
                .containsExactly(
                        new ChildResponse(
                                active.getChildId(), classId, "테스트아동1", ChildStatus.ACTIVE),
                        new ChildResponse(
                                paused.getChildId(), classId, "테스트아동2", ChildStatus.PAUSED));
    }
}
