package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.child.dto.CreateChildRequest;
import com.neuringo.neuringobe.classroom.dto.CreateClassroomRequest;
import com.neuringo.neuringobe.user.dto.SignupRequest;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DB 스키마가 개인정보 최소 수집과 API 합의를 지키는지 본다. {@link SchemaConstraintIntegrationTest} 가 값(enum·CHECK·FK)을
 * 넣어 보고, 여기서는 스키마 정의 자체(information_schema)를 본다.
 *
 * <ul>
 *   <li>아동 테이블에는 ID·학급·실명·상태만 있다(VS-002 "생년월일·성별·학교·보호자 연락처는 입력받거나 저장하지 않는다"). 컬럼을 늘리면 이 테스트가 먼저
 *       깨진다. 늘릴 때는 PRV(개인정보) 검토와 함께 이 목록을 고친다.
 *   <li>학급·강사 계정·로그인 세션 테이블도 정해진 컬럼만 있다. 로그인 세션에는 토큰 원문 컬럼이 없다(해시만).
 *   <li>이름·이메일 컬럼 길이(VARCHAR)가 요청 검증 {@code @Size(max)} 와 같다. 한쪽만 바뀌면 검증을 통과한 값이 DB 에서 500 으로 터진다.
 *   <li>꼭 있어야 하는 값은 DB 에서도 NOT NULL 이다(애플리케이션 검증을 건너뛴 값도 막는다).
 *   <li>학급의 강사는 강사 계정을 가리키는 UUID 다(VS-001 "자신이 담당하는 학급과 아동만").
 *   <li>학급별 아동 조회, 강사별 학급 조회, 사용자별 세션 조회에 쓰는 인덱스가 있다.
 *   <li>마이그레이션이 전부 성공 상태로 적용됐고 남은 것이 없다.
 * </ul>
 */
@IntegrationTest
class SchemaIntegrityIntegrationTest {

    private static final Map<String, Class<?>> REQUESTS =
            Map.of(
                    "CreateClassroomRequest", CreateClassroomRequest.class,
                    "CreateChildRequest", CreateChildRequest.class,
                    "SignupRequest", SignupRequest.class);

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private Flyway flyway;

    @Test
    void childTableHoldsOnlyIdClassroomNameAndStatus() {
        assertThat(columnsOf("child"))
                .containsExactlyInAnyOrder("child_id", "class_id", "display_name", "status");
    }

    @Test
    void classroomTableHoldsOnlyTheAgreedColumns() {
        assertThat(columnsOf("classroom"))
                .containsExactlyInAnyOrder("class_id", "instructor_id", "name", "status");
    }

    @Test
    void userAccountTableHoldsOnlyTheAgreedColumns() {
        assertThat(columnsOf("user_account"))
                .containsExactlyInAnyOrder(
                        "user_id",
                        "email",
                        "password_hash",
                        "name",
                        "org_name",
                        "role",
                        "status",
                        "created_at");
    }

    @Test
    void authSessionTableKeepsNoRawToken() {
        assertThat(columnsOf("auth_session"))
                .containsExactlyInAnyOrder(
                        "session_id", "user_id", "token_hash", "expires_at", "created_at");
    }

    @ParameterizedTest(name = "{0}.{1} ↔ {2}.{3}")
    @CsvSource({
        "classroom, name, CreateClassroomRequest, name",
        "child, display_name, CreateChildRequest, displayName",
        "user_account, email, SignupRequest, email",
        "user_account, name, SignupRequest, name",
        "user_account, org_name, SignupRequest, orgName"
    })
    void textColumnLengthMatchesRequestValidation(
            String table, String column, String requestType, String field) throws Exception {
        Size size = REQUESTS.get(requestType).getDeclaredField(field).getAnnotation(Size.class);

        Integer columnLength =
                jdbcTemplate.queryForObject(
                        "SELECT character_maximum_length FROM information_schema.columns"
                                + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                        Integer.class,
                        table,
                        column);

        assertThat(size).as("%s.%s 의 @Size", requestType, field).isNotNull();
        assertThat(columnLength).isEqualTo(size.max());
    }

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({
        "classroom, instructor_id",
        "classroom, name",
        "classroom, status",
        "child, class_id",
        "child, display_name",
        "child, status",
        "user_account, email",
        "user_account, password_hash",
        "user_account, name",
        "user_account, role",
        "user_account, status",
        "user_account, created_at",
        "auth_session, user_id",
        "auth_session, token_hash",
        "auth_session, expires_at",
        "auth_session, created_at"
    })
    void requiredColumnsAreNotNull(String table, String column) {
        String nullable =
                jdbcTemplate.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns"
                                + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                        String.class,
                        table,
                        column);

        assertThat(nullable).isEqualTo("NO");
    }

    @Test
    void classroomInstructorIsAUuidReferencingUserAccount() {
        String type =
                jdbcTemplate.queryForObject(
                        "SELECT data_type FROM information_schema.columns"
                                + " WHERE table_schema = current_schema() AND table_name = 'classroom'"
                                + " AND column_name = 'instructor_id'",
                        String.class);
        List<String> references =
                jdbcTemplate.queryForList(
                        "SELECT ccu.table_name || '.' || ccu.column_name"
                                + " FROM information_schema.table_constraints tc"
                                + " JOIN information_schema.key_column_usage kcu"
                                + "   ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema"
                                + " JOIN information_schema.constraint_column_usage ccu"
                                + "   ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema"
                                + " WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = current_schema()"
                                + " AND tc.table_name = 'classroom' AND kcu.column_name = 'instructor_id'",
                        String.class);

        assertThat(type).isEqualTo("uuid");
        assertThat(references).containsExactly("user_account.user_id");
    }

    @ParameterizedTest(name = "{0}({1})")
    @CsvSource({"child, class_id", "classroom, instructor_id", "auth_session, user_id"})
    void indexesTheLookupColumns(String table, String column) {
        List<String> indexes =
                jdbcTemplate.queryForList(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND tablename = ?",
                        String.class,
                        table);

        assertThat(indexes).anySatisfy(index -> assertThat(index).contains("(" + column + ")"));
    }

    @Test
    void appliesEveryMigrationSuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(applied)
                .isNotEmpty()
                .allSatisfy(
                        migration ->
                                assertThat(migration.getState().isFailed())
                                        .as(
                                                "V%s %s",
                                                migration.getVersion(), migration.getDescription())
                                        .isFalse());
        assertThat(applied)
                .anySatisfy(
                        migration ->
                                assertThat(migration.getVersion().getVersion()).isEqualTo("1"));
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ?",
                String.class,
                table);
    }
}
