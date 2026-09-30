package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 등록 요청이 동시에 몰려도 하나도 빠지거나 다른 학급에 섞이지 않는다(06 DoD "정상·주요 예외·중복 요청 테스트", VS-002 동명이인).
 *
 * <ul>
 *   <li>한 학급에 동시에 들어온 서로 다른 아동 등록은 전부 한 번씩 저장된다.
 *   <li>두 학급에 동시에 등록해도 각 학급 목록에는 자기 아동만 있다.
 *   <li>같은 이름(동명이인)을 동시에 등록해도 각자 다른 아동으로 저장된다.
 *   <li>같은 이메일로 동시에 가입하면 한 명만 가입되고 나머지는 409 다(VS-001 "이메일은 … 서비스 전체에서 중복 등록되지 않는다"). 대소문자·앞뒤 공백만 다른
 *       이메일도 같은 이메일이다.
 * </ul>
 *
 * <p>서버는 같은 요청 두 번을 하나로 합치지 않는다. 버튼 중복 제출은 프론트(#17)가 막고, 요청 멱등성은 S3(DEC-010)에서 정한다. 여기서는 동시에 들어온 서로
 * 다른 등록이 유실·혼입 없이 저장되는지만 본다.
 */
@LocalProfileIntegrationTest
class ConcurrentRegistrationIntegrationTest {

    private static final int REQUESTS = 12;

    @Autowired private TestFixtures fixtures;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void storesEveryConcurrentRegistrationExactlyOnce() throws Exception {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);

        List<String> childIds = registerConcurrently(i -> classId, i -> "동시 등록 아동 " + i);

        assertThat(childIds).doesNotHaveDuplicates().hasSize(REQUESTS);
        assertThat(childIdsOf(classId)).containsExactlyInAnyOrderElementsOf(childIds);
    }

    @Test
    void keepsConcurrentRegistrationsInTheirOwnClassrooms() throws Exception {
        UUID a1 = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);
        UUID b1 = fixtures.createClassroom(TestFixtures.CLASSROOM_B1);

        List<String> childIds =
                registerConcurrently(i -> i % 2 == 0 ? a1 : b1, i -> "섞이면 안 되는 아동 " + i);

        List<String> a1Children = new ArrayList<>();
        List<String> b1Children = new ArrayList<>();
        for (int i = 0; i < childIds.size(); i++) {
            (i % 2 == 0 ? a1Children : b1Children).add(childIds.get(i));
        }
        assertThat(childIdsOf(a1)).containsExactlyInAnyOrderElementsOf(a1Children);
        assertThat(childIdsOf(b1)).containsExactlyInAnyOrderElementsOf(b1Children);
    }

    @Test
    void storesConcurrentNamesakesAsSeparateChildren() throws Exception {
        UUID classId = fixtures.createClassroom(TestFixtures.CLASSROOM_A1);

        List<String> childIds = registerConcurrently(i -> classId, i -> TestFixtures.NAMESAKE);

        assertThat(childIds).doesNotHaveDuplicates().hasSize(REQUESTS);
        String json =
                TestFixtures.body(fixtures.get("/api/v1/classrooms/{classId}/children", classId));
        assertThat(JsonPath.<List<String>>read(json, "$.data[*].displayName"))
                .hasSize(REQUESTS)
                .containsOnly(TestFixtures.NAMESAKE);
    }

    @Test
    void signsUpOnlyOneOfConcurrentSignupsWithTheSameEmail() throws Exception {
        TestFixtures.Credentials credentials = TestFixtures.newCredentials();
        String email = credentials.email();

        List<MvcTestResult> results =
                concurrently(
                        i -> {
                            // 대소문자·앞뒤 공백을 섞어도 정규화하면 같은 이메일이다.
                            String variant = i % 2 == 0 ? email : " " + email.toUpperCase() + " ";
                            return fixtures.signUpRequest(
                                    variant, credentials.password(), TestFixtures.INSTRUCTOR_NAME);
                        });

        List<Integer> statuses = results.stream().map(r -> r.getResponse().getStatus()).toList();
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses)
                .filteredOn(status -> status != 200)
                .hasSize(REQUESTS - 1)
                .containsOnly(409);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM user_account WHERE email = ?",
                                Integer.class,
                                email))
                .isEqualTo(1);
    }

    /** i 번째 요청을 classIdOf(i) 학급에 nameOf(i) 이름으로, 한꺼번에 출발시킨다. 돌려주는 목록은 i 순서의 childId 다. */
    private List<String> registerConcurrently(
            IntFunction<UUID> classIdOf, IntFunction<String> nameOf) throws Exception {
        List<MvcTestResult> results =
                concurrently(
                        index ->
                                fixtures.postJson(
                                        "/api/v1/classrooms/"
                                                + classIdOf.apply(index)
                                                + "/children",
                                        Map.of("displayName", nameOf.apply(index))));

        List<String> childIds = new ArrayList<>();
        for (MvcTestResult result : results) {
            assertThat(result).hasStatusOk();
            childIds.add(JsonPath.read(TestFixtures.body(result), "$.data.childId"));
        }
        return childIds;
    }

    /** request(i) 를 REQUESTS 개 스레드에서 한꺼번에 출발시킨다. 돌려주는 목록은 i 순서다. */
    private List<MvcTestResult> concurrently(IntFunction<MvcTestResult> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcTestResult>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                int index = i;
                Callable<MvcTestResult> task =
                        () -> {
                            start.await();
                            return request.apply(index);
                        };
                futures.add(pool.submit(task));
            }
            start.countDown();

            List<MvcTestResult> results = new ArrayList<>();
            for (Future<MvcTestResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private List<String> childIdsOf(UUID classId) {
        String json =
                TestFixtures.body(fixtures.get("/api/v1/classrooms/{classId}/children", classId));
        return JsonPath.read(json, "$.data[*].childId");
    }
}
