package com.neuringo.neuringobe.classroom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.neuringo.neuringobe.classroom.domain.Classroom;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.classroom.dto.ClassroomResponse;
import com.neuringo.neuringobe.classroom.dto.CreateClassroomRequest;
import com.neuringo.neuringobe.classroom.repository.ClassroomRepository;
import com.neuringo.neuringobe.common.ResourceNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClassroomServiceTest {

    @Mock private ClassroomRepository classroomRepository;

    @InjectMocks private ClassroomService classroomService;

    @Test
    void rejectsMissingClassroomWithNotFound() {
        UUID classId = UUID.randomUUID();
        given(classroomRepository.existsById(classId)).willReturn(false);

        assertThatThrownBy(() -> classroomService.validateClassroomExists(classId))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting("code")
                .isEqualTo("CLASSROOM_NOT_FOUND");
    }

    @Test
    void savesNewClassroomAsActiveForGivenInstructor() {
        given(classroomRepository.save(any(Classroom.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ClassroomResponse response =
                classroomService.create("test-instructor", new CreateClassroomRequest("테스트반"));

        ArgumentCaptor<Classroom> saved = ArgumentCaptor.forClass(Classroom.class);
        verify(classroomRepository).save(saved.capture());
        Classroom classroom = saved.getValue();
        assertThat(classroom.getClassId()).isNotNull();
        assertThat(classroom.getInstructorId()).isEqualTo("test-instructor");
        assertThat(classroom.getName()).isEqualTo("테스트반");
        assertThat(classroom.getStatus()).isEqualTo(ClassroomStatus.ACTIVE);

        assertThat(response.classId()).isEqualTo(classroom.getClassId());
        assertThat(response.instructorId()).isEqualTo("test-instructor");
        assertThat(response.name()).isEqualTo("테스트반");
        assertThat(response.status()).isEqualTo(ClassroomStatus.ACTIVE);
    }
}
