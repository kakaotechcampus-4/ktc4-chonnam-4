package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 강사의 민감 조회 기록. 조회에 성공했을 때만 강사 ID·대상 종류·대상 ID 한 줄이 남고, 아동 이름은 남지 않는다. 막힌 조회(남의 아동·없는 아동)는 남기지 않는다.
 */
@LocalProfileIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SensitiveReadLogIntegrationTest {

    @Autowired private TestFixtures fixtures;

    @Test
    void successfulChildReadLeavesOneLineWithIdsOnly(CapturedOutput output) {
        TestFixtures.Instructor owner = fixtures.signUpInstructor();
        String name = "기록확인아동" + UUID.randomUUID().toString().substring(0, 8);
        UUID classId = fixtures.createClassroom(owner, TestFixtures.CLASSROOM_A1);
        UUID childId = fixtures.createChild(owner, classId, name);

        assertThat(fixtures.get(owner, "/api/v1/children/{childId}", childId)).hasStatusOk();

        assertThat(output.getOut())
                .contains(
                        "SENSITIVE_READ instructorId="
                                + owner.userId()
                                + " target=CHILD targetId="
                                + childId)
                .doesNotContain(name);
    }

    @Test
    void blockedReadsLeaveNoLine(CapturedOutput output) {
        TestFixtures.Instructor owner = fixtures.signUpInstructor();
        TestFixtures.Instructor other = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, TestFixtures.CLASSROOM_A1);
        UUID childId = fixtures.createChild(owner, classId, "남의 아동");

        assertThat(fixtures.get(other, "/api/v1/children/{childId}", childId))
                .hasStatus4xxClientError();
        assertThat(fixtures.get(other, "/api/v1/children/{childId}", UUID.randomUUID()))
                .hasStatus4xxClientError();

        assertThat(output.getOut()).doesNotContain("SENSITIVE_READ instructorId=" + other.userId());
    }
}
