package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 강사의 민감 조회 기록. 성공한 조회와 막힌 조회(남의 아동·없는 아동) 모두 강사 ID·대상 종류·대상 ID·결과 한 줄이 남고, 아동 이름은 남지 않는다. */
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
                                + childId
                                + " outcome=ALLOWED")
                .doesNotContain(name);
    }

    @Test
    void blockedReadsLeaveBlockedLineWithErrorCode(CapturedOutput output) {
        TestFixtures.Instructor owner = fixtures.signUpInstructor();
        TestFixtures.Instructor other = fixtures.signUpInstructor();
        UUID classId = fixtures.createClassroom(owner, TestFixtures.CLASSROOM_A1);
        UUID childId = fixtures.createChild(owner, classId, "남의 아동");

        assertThat(fixtures.get(other, "/api/v1/children/{childId}", childId))
                .hasStatus4xxClientError();
        UUID missingId = UUID.randomUUID();
        assertThat(fixtures.get(other, "/api/v1/children/{childId}", missingId))
                .hasStatus4xxClientError();

        // 남의 아동과 없는 아동은 같은 줄이 된다. 서버가 남의 자원을 없는 자원처럼 숨기기 때문이다.
        assertThat(output.getOut())
                .contains(blockedChildLine(other, childId))
                .contains(blockedChildLine(other, missingId))
                .doesNotContain("남의 아동")
                .doesNotContain(
                        "SENSITIVE_READ instructorId="
                                + other.userId()
                                + " target=CHILD targetId="
                                + childId
                                + " outcome=ALLOWED");
    }

    private static String blockedChildLine(TestFixtures.Instructor instructor, UUID childId) {
        return "SENSITIVE_READ instructorId="
                + instructor.userId()
                + " target=CHILD targetId="
                + childId
                + " outcome=BLOCKED status=404 code=CHILD_NOT_FOUND";
    }
}
