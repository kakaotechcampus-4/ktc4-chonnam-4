package com.neuringo.neuringobe.ai.infrastructure.speech;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Docker 없이 실제 HTTP 로 어댑터를 검증하는 테스트용 서버. 응답 하나를 정해 두고 받은 요청을 기록한다. */
final class StubHttpServer implements AutoCloseable {

    record RecordedRequest(
            String method, String path, Map<String, List<String>> headers, byte[] body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }

        String bodyAsString() {
            return new String(body, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    private final HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String contentType = "application/json";
    private volatile byte[] body = new byte[0];
    private volatile Duration delay = Duration.ZERO;

    StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.add(
                            new RecordedRequest(
                                    exchange.getRequestMethod(),
                                    exchange.getRequestURI().getPath(),
                                    Map.copyOf(exchange.getRequestHeaders()),
                                    exchange.getRequestBody().readAllBytes()));
                    try {
                        Thread.sleep(delay.toMillis());
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    if (contentType != null) {
                        exchange.getResponseHeaders().add("Content-Type", contentType);
                    }
                    byte[] responseBody = body;
                    exchange.sendResponseHeaders(
                            status, responseBody.length == 0 ? -1 : responseBody.length);
                    try (OutputStream output = exchange.getResponseBody()) {
                        output.write(responseBody);
                    } catch (IOException ignored) {
                        // 클라이언트가 제한 시간으로 먼저 끊은 경우
                    }
                });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respond(int status, String contentType, byte[] body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    void respondJson(int status, String json) {
        respond(status, "application/json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    void delay(Duration delay) {
        this.delay = delay;
    }

    List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
