package com.neuringo.neuringobe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.jayway.jsonpath.JsonPath;
import com.neuringo.neuringobe.auth.service.AuthService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 필터 단계 오류가 /error 재진입을 거쳐도 원래 상태로 응답하는지 실제 서버로 확인한다(PR #35 리뷰).
 *
 * <p>MockMvc 는 서블릿 컨테이너의 /error 재진입을 흉내 내지 않아 이 버그가 보이지 않는다. 그래서 {@link IntegrationTest} 대신 실제 포트로
 * 서버를 띄운다. 컨텍스트가 하나 더 뜨는 비용은 이 클래스 하나로 한정한다.
 *
 * <ul>
 *   <li>CSRF 실패는 403 CSRF_TOKEN_INVALID 다. 예전에는 /error 재진입에서 익명으로 막혀 401
 *       AUTHENTICATION_REQUIRED·path /error 가 나갔다.
 *   <li>토큰 조회 중 DB 장애처럼 필터에서 던진 예외는 500 INTERNAL_ERROR 다. 예전에는 같은 경로로 401 이 되어 프론트가 로그인 화면으로 보냈다.
 *   <li>두 경우 모두 path 는 /error 가 아니라 원래 요청 경로이고, X-Trace-Id 헤더는 본문의 traceId 와 같다.
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ErrorDispatchIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort private int port;

    @MockitoBean private AuthService authService;

    @Test
    void answersCsrfFailureAsCsrfErrorOnRealServer() throws Exception {
        HttpResponse<String> response =
                send(
                        HttpRequest.newBuilder(uri("/api/v1/auth/session"))
                                .DELETE()
                                .header("Authorization", "Bearer some-token")
                                .build());

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code"))
                .isEqualTo("CSRF_TOKEN_INVALID");
        assertThat(JsonPath.<String>read(response.body(), "$.error.path"))
                .isEqualTo("/api/v1/auth/session");
        assertTraceHeaderMatchesBody(response);
    }

    @Test
    void answersExceptionThrownInFilterAsServerErrorNotLogin() throws Exception {
        given(authService.authenticate(anyString()))
                .willThrow(new DataAccessResourceFailureException("connection refused"));

        HttpResponse<String> response =
                send(
                        HttpRequest.newBuilder(uri("/api/v1/classrooms"))
                                .GET()
                                .header("Authorization", "Bearer some-token")
                                .build());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(JsonPath.<String>read(response.body(), "$.error.code"))
                .isEqualTo("INTERNAL_ERROR");
        assertThat(JsonPath.<String>read(response.body(), "$.error.path"))
                .isEqualTo("/api/v1/classrooms");
        // 예외 메시지는 응답에 싣지 않는다.
        assertThat(response.body()).doesNotContain("connection refused");
        assertTraceHeaderMatchesBody(response);
    }

    // 다른 오류 응답과 같이 X-Trace-Id 헤더가 본문의 traceId 와 같아야 로그를 따라갈 수 있다.
    private static void assertTraceHeaderMatchesBody(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("X-Trace-Id"))
                .contains(JsonPath.<String>read(response.body(), "$.error.traceId"));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
