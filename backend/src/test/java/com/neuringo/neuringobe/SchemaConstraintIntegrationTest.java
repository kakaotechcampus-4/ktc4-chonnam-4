package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.neuringo.neuringobe.child.domain.ChildStatus;
import com.neuringo.neuringobe.classroom.domain.ClassroomStatus;
import com.neuringo.neuringobe.user.domain.AccountStatus;
import com.neuringo.neuringobe.user.domain.UserRole;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB 스키마(Flyway V*.sql)와 Java 코드가 같은 약속을 지키는지 본다.
 *
 * <ul>
 *   <li>enum ↔ CHECK: Java enum 의 모든 값이 status·role 컬럼의 CHECK 와 길이(VARCHAR(20))를 통과한다. JPA 는
 *       {@code @Enumerated(STRING)} 이라 name() 을 그대로 저장하므로, enum 값을 추가하고 마이그레이션을 빠뜨리면 그 값을 저장하는 순간
 *       터진다.
 *   <li>CHECK 는 enum 에 없는 값을 막는다. CHECK 가 사라져서 위 검사가 저절로 통과하는 일을 막는다.
 *   <li>FK: 없는 학급에 아동을, 없는 강사에게 학급·로그인 세션을 넣으면 DB 가 23503(foreign_key_violation)으로 막는다.
 *   <li>UNIQUE: 같은 이메일의 계정, 같은 토큰 해시의 세션은 DB 가 23505(unique_violation)로 막는다(VS-001 "이메일은 … 중복 등록되지
 *       않는다" 의 마지막 방어선 — 동시 가입은 이 제약 하나로 가린다).
 * </ul>
 *
 * <p>트랜잭션 없이 JdbcTemplate 으로 바로 넣는다. 예외 타입은 단정하지 않고 SQLSTATE 만 본다.
 */
@IntegrationTest
class SchemaConstraintIntegrationTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String UNIQUE_VIOLATION = "23505";

    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(ClassroomStatus.class)
    void classroomStatusColumnAcceptsEveryEnumValue(ClassroomStatus status) {
        UUID instructorId = insertInstructor();

        assertThatCode(() -> insertClassroom(instructorId, status.name()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(ChildStatus.class)
    void childStatusColumnAcceptsEveryEnumValue(ChildStatus status) {
        UUID classId = insertClassroom(insertInstructor(), ClassroomStatus.ACTIVE.name());

        assertThatCode(() -> insertChild(classId, status.name())).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void userRoleColumnAcceptsEveryEnumValue(UserRole role) {
        assertThatCode(() -> insertUser(newEmail(), role.name(), AccountStatus.ACTIVE.name()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(AccountStatus.class)
    void accountStatusColumnAcceptsEveryEnumValue(AccountStatus status) {
        assertThatCode(() -> insertUser(newEmail(), UserRole.INSTRUCTOR.name(), status.name()))
                .doesNotThrowAnyException();
    }

    @Test
    void statusColumnsRejectValuesOutsideTheEnums() {
        UUID classId = insertClassroom(insertInstructor(), ClassroomStatus.ACTIVE.name());

        assertThat(sqlStateOf(() -> insertClassroom(insertInstructor(), "UNKNOWN")))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(sqlStateOf(() -> insertChild(classId, "UNKNOWN"))).isEqualTo(CHECK_VIOLATION);
        assertThat(sqlStateOf(() -> insertUser(newEmail(), "ADMIN", AccountStatus.ACTIVE.name())))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(sqlStateOf(() -> insertUser(newEmail(), UserRole.INSTRUCTOR.name(), "UNKNOWN")))
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void childRequiresExistingClassroom() {
        assertThat(sqlStateOf(() -> insertChild(UUID.randomUUID(), ChildStatus.ACTIVE.name())))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    void classroomRequiresExistingInstructor() {
        assertThat(
                        sqlStateOf(
                                () ->
                                        insertClassroom(
                                                UUID.randomUUID(), ClassroomStatus.ACTIVE.name())))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    void sessionRequiresExistingUser() {
        assertThat(sqlStateOf(() -> insertSession(UUID.randomUUID(), newTokenHash())))
                .isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    void emailIsUniqueAcrossAccounts() {
        String email = newEmail();
        insertUser(email, UserRole.INSTRUCTOR.name(), AccountStatus.ACTIVE.name());

        assertThat(
                        sqlStateOf(
                                () ->
                                        insertUser(
                                                email,
                                                UserRole.INSTRUCTOR.name(),
                                                AccountStatus.ACTIVE.name())))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void tokenHashIsUniqueAcrossSessions() {
        UUID userId = insertInstructor();
        String tokenHash = newTokenHash();
        insertSession(userId, tokenHash);

        assertThat(sqlStateOf(() -> insertSession(userId, tokenHash))).isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void onlyOneNotStartedActivityPerChildAndGoal() {
        UUID instructorId = insertInstructor();
        UUID childId = insertChild(insertClassroom(instructorId, "ACTIVE"), "ACTIVE");
        UUID goalId = insertGoal(childId, instructorId);
        insertActivity(childId, goalId, "COMPLETED", null);
        insertActivity(childId, goalId, "NOT_STARTED", null);

        assertThat(sqlStateOf(() -> insertActivity(childId, goalId, "NOT_STARTED", null)))
                .isEqualTo(UNIQUE_VIOLATION);
        UUID otherGoal = insertGoal(childId, instructorId);
        assertThatCode(() -> insertActivity(childId, otherGoal, "NOT_STARTED", null))
                .doesNotThrowAnyException();
    }

    @Test
    void activityRequestKeyIsUnique() {
        UUID instructorId = insertInstructor();
        UUID childId = insertChild(insertClassroom(instructorId, "ACTIVE"), "ACTIVE");
        UUID key = UUID.randomUUID();
        insertActivity(childId, insertGoal(childId, instructorId), "NOT_STARTED", key);
        UUID otherGoal = insertGoal(childId, instructorId);

        assertThat(sqlStateOf(() -> insertActivity(childId, otherGoal, "NOT_STARTED", key)))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    private UUID insertInstructor() {
        return insertUser(newEmail(), UserRole.INSTRUCTOR.name(), AccountStatus.ACTIVE.name());
    }

    private UUID insertUser(String email, String role, String status) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO user_account (user_id, email, password_hash, name, role, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId,
                email,
                "schema-test-hash",
                "스키마 검사 강사",
                role,
                status,
                Timestamp.from(Instant.now()));
        return userId;
    }

    private UUID insertClassroom(UUID instructorId, String status) {
        UUID classId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO classroom (class_id, instructor_id, name, status) VALUES (?, ?, ?, ?)",
                classId,
                instructorId,
                "스키마 검사 학급",
                status);
        return classId;
    }

    private UUID insertChild(UUID classId, String status) {
        UUID childId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO child (child_id, class_id, display_name, status) VALUES (?, ?, ?, ?)",
                childId,
                classId,
                "스키마 검사 아동",
                status);
        return childId;
    }

    private UUID insertGoal(UUID childId, UUID instructorId) {
        UUID goalId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO learning_goal (goal_id, child_id, instructor_id, title, content_hash, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                goalId,
                childId,
                instructorId,
                "스키마 검사 목표",
                "schema-test-hash",
                Timestamp.from(Instant.now()));
        return goalId;
    }

    private void insertActivity(UUID childId, UUID goalId, String status, UUID idempotencyKey) {
        jdbcTemplate.update(
                "INSERT INTO activity (activity_id, child_id, goal_id, status, assigned_at, idempotency_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                childId,
                goalId,
                status,
                Timestamp.from(Instant.now()),
                idempotencyKey);
    }

    private void insertSession(UUID userId, String tokenHash) {
        Instant now = Instant.now();
        jdbcTemplate.update(
                "INSERT INTO auth_session (session_id, user_id, token_hash, expires_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                userId,
                tokenHash,
                Timestamp.from(now.plus(Duration.ofHours(1))),
                Timestamp.from(now));
    }

    private static String newEmail() {
        return "schema-" + UUID.randomUUID() + "@example.com";
    }

    private static String newTokenHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static String sqlStateOf(ThrowingCallable statement) {
        Throwable thrown = catchThrowable(statement);
        assertThat(thrown).as("DB 가 막아야 한다").isNotNull();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        throw new AssertionError("원인에 SQLException 이 없다", thrown);
    }
}
