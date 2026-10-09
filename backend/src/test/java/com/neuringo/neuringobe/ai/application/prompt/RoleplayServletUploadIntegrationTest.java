package com.neuringo.neuringobe.ai.application.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.IntegrationTest;
import com.neuringo.neuringobe.child.service.ChildAccessCodeService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RoleplayHttpTestConfiguration.class)
@TestPropertySource(
        properties = {
            "roleplay.http.enabled=true",
            "server.servlet.session.cookie.secure=false",
            "roleplay.http.maximum-upload-bytes=16",
            "roleplay.http.maximum-request-bytes=65536",
            "roleplay.http.allowed-formats=WEBM",
            "roleplay.http.worker-count=2",
            "roleplay.http.queue-capacity=4",
            "roleplay.retention.cleanup-enabled=false",
            "neuringo.child-access.hmac-secret=test-only-hmac-secret-that-is-at-least-32-bytes-long"
        })
class RoleplayServletUploadIntegrationTest {
    static final Path UPLOADS = RoleplayHttpIntegrationTest.directory();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("roleplay.http.spool-directory", UPLOADS::toString);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ChildAccessCodeService codes;
    @Autowired RoleplayHttpTestConfiguration.State state;

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void reset() {
        state.reset();
    }

    @AfterAll
    static void cleanup() throws Exception {
        Files.delete(UPLOADS);
    }

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void realMultipartTransportBindsAuthenticatedRequestAndCleansBothSpools() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var client = login(f);
        var response = upload(client, f.session(), UUID.randomUUID(), new byte[] {1, 2, 3});
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(response.body()).at("/data/status").asText())
                .isEqualTo("DELIVERED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Long.class,
                                f.session()))
                .isEqualTo(1);
        assertEmptySpool();
    }

    @Test
    void servletEnforcesFileLimitBeforeControllerWithoutAiOrCheckpoint() throws Exception {
        var f = RoleplayHttpFixtures.fixture(jdbc);
        var client = login(f);
        var response = upload(client, f.session(), UUID.randomUUID(), new byte[17]);
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(mapper.readTree(response.body()).at("/error/status").asInt()).isEqualTo(413);
        assertThat(state.calls).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from roleplay_turn where session_id=?",
                                Long.class,
                                f.session()))
                .isZero();
        assertEmptySpool();
    }

    private Client login(RoleplayHttpFixtures.Fixture f) throws Exception {
        var http =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .connectTimeout(Duration.ofSeconds(3))
                        .build();
        var csrf =
                http.send(
                        HttpRequest.newBuilder(url("/api/v1/csrf")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        var token = mapper.readTree(csrf.body()).get("data");
        var code =
                codes.issue(f.child(), UUID.randomUUID(), f.instructor()).response().accessCode();
        var entered =
                http.send(
                        HttpRequest.newBuilder(url("/api/v1/child-access-sessions"))
                                .header(
                                        token.get("headerName").asText(),
                                        token.get("token").asText())
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"accessCode\":\"" + code + "\"}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(entered.statusCode()).isEqualTo(201);
        var refreshed =
                http.send(
                        HttpRequest.newBuilder(url("/api/v1/csrf")).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
        var current = mapper.readTree(refreshed.body()).get("data");
        return new Client(http, current.get("headerName").asText(), current.get("token").asText());
    }

    private HttpResponse<String> upload(Client client, UUID session, UUID key, byte[] audio)
            throws Exception {
        String boundary = "neuringo-roleplay-servlet-boundary";
        var body = new java.io.ByteArrayOutputStream();
        body.write(
                ("--"
                                + boundary
                                + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"ignored.webm\"\r\nContent-Type: audio/webm\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write(audio);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.http.send(
                HttpRequest.newBuilder(
                                url("/api/v1/roleplay-sessions/" + session + "/voice-inputs"))
                        .header(client.header, client.token)
                        .header("Idempotency-Key", key.toString())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertEmptySpool() throws Exception {
        long until = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        do {
            try (var files = Files.list(UPLOADS)) {
                if (files.count() == 0) return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        try (var files = Files.list(UPLOADS)) {
            assertThat(files.count()).isZero();
        }
    }

    private record Client(HttpClient http, String header, String token) {}
}
