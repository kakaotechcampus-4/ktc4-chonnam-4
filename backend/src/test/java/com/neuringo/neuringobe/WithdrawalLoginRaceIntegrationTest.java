package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * 탈퇴가 세션을 지운 뒤·계정을 지우기 전에 다른 탭의 로그인이 들어와도, 로그인은 탈퇴가 끝나기를 기다렸다가 일반 로그인 오류를 받고 탈퇴는 성공한다. 예전에는 로그인이 그
 * 틈에 새 세션을 저장해 계정 삭제가 FK 에 막히고 탈퇴가 500 으로 되돌아갔다.
 *
 * <p>탈퇴를 바로 그 틈에서 멈추려고 실제 세션 저장소 호출을 감싸, 세션 DELETE 가 끝난 직후 기다리게 한다. 쿼리는 그대로 실행한다. 탈퇴 시작의 계정 잠금이 빠지면
 * 로그인이 기다리지 않고 끝나 이 테스트가 실패한다. 저장소를 감싸느라 컨텍스트가 하나 더 뜨는 비용은 이 클래스 하나로 한정한다.
 */
@LocalProfileIntegrationTest
@Import(WithdrawalLoginRaceIntegrationTest.PauseAfterSessionDelete.class)
class WithdrawalLoginRaceIntegrationTest {

    /** 멈출 강사와 신호. 다른 테스트의 호출에 끼어들지 않도록 이 강사의 호출에서만 멈춘다. */
    private static volatile Gate gate;

    @Autowired private TestFixtures fixtures;

    @Autowired private MockMvcTester mvc;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void loginBetweenSessionAndAccountDeleteWaitsAndIsRejectedWhileWithdrawalSucceeds()
            throws Exception {
        TestFixtures.Instructor instructor = fixtures.signUpInstructor();
        TestFixtures.CsrfCredentials csrf = fixtures.fetchCsrf();
        Gate current = new Gate(instructor.userId());
        gate = current;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcTestResult> withdrawal =
                    pool.submit(
                            () ->
                                    TestFixtures.bearer(
                                                    mvc.delete()
                                                            .uri("/api/v1/users/me")
                                                            .header(csrf.headerName(), csrf.token())
                                                            .cookie(csrf.cookies()),
                                                    instructor.accessToken())
                                            .exchange());
            assertThat(current.sessionsDeleted.await(30, TimeUnit.SECONDS)).isTrue();

            Future<MvcTestResult> login =
                    pool.submit(
                            () -> fixtures.loginRequest(instructor.email(), instructor.password()));
            // 로그인이 비밀번호 검증을 지나 계정 잠금을 요청하는 지점까지 온 뒤에도, 탈퇴가 끝나기 전에는 끝나지 않아야 한다.
            assertThat(current.loginLocking.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(catchThrowable(() -> login.get(1, TimeUnit.SECONDS)))
                    .isInstanceOf(TimeoutException.class);

            current.resume.countDown();
            assertThat(withdrawal.get(30, TimeUnit.SECONDS)).hasStatus(HttpStatus.NO_CONTENT);
            MvcTestResult rejected = login.get(30, TimeUnit.SECONDS);
            assertThat(rejected).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(rejected)
                    .bodyJson()
                    .extractingPath("$.error.code")
                    .isEqualTo("INVALID_CREDENTIALS");
        } finally {
            current.resume.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(35, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
            gate = null;
        }
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM user_account WHERE user_id = ?",
                                Long.class,
                                instructor.userId()))
                .isZero();
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM auth_session WHERE user_id = ?",
                                Long.class,
                                instructor.userId()))
                .isZero();
    }

    private static final class Gate {
        private final UUID userId;
        private final CountDownLatch sessionsDeleted = new CountDownLatch(1);
        private final CountDownLatch loginLocking = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);

        private Gate(UUID userId) {
            this.userId = userId;
        }

        private boolean matches(Object[] arguments) {
            return arguments.length == 1 && userId.equals(arguments[0]);
        }
    }

    /** 세션 저장소의 {@code deleteByUserId} 가 끝난 직후 멈추고, 계정 저장소의 {@code findForShare} 진입을 알린다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class PauseAfterSessionDelete {

        @Bean
        static BeanPostProcessor pauseAfterSessionDelete() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!name.equals("authSessionRepository")
                            && !name.equals("userAccountRepository")) {
                        return bean;
                    }
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.addAdvice(
                            (MethodInterceptor)
                                    call -> {
                                        Gate current = gate;
                                        boolean target =
                                                current != null
                                                        && current.matches(call.getArguments());
                                        String method = call.getMethod().getName();
                                        if (target && method.equals("findForShare")) {
                                            current.loginLocking.countDown();
                                        }
                                        Object result = call.proceed();
                                        if (target
                                                && name.equals("authSessionRepository")
                                                && method.equals("deleteByUserId")) {
                                            current.sessionsDeleted.countDown();
                                            if (!current.resume.await(30, TimeUnit.SECONDS)) {
                                                throw new AssertionError("탈퇴 재개 신호를 받지 못했다");
                                            }
                                        }
                                        return result;
                                    });
                    return proxy.getProxy();
                }
            };
        }
    }
}
