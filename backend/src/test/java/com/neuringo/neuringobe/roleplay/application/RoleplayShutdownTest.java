package com.neuringo.neuringobe.roleplay.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.neuringo.neuringobe.ai.application.model.AudioFormat;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnDeadline;
import com.neuringo.neuringobe.ai.application.roleplay.RoleplayTurnRunner;
import com.neuringo.neuringobe.roleplay.config.RoleplayHttpConfiguration;
import com.neuringo.neuringobe.roleplay.config.RoleplayHttpProperties;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

class RoleplayShutdownTest {
    @TempDir Path directory;

    @Test
    void deploymentWaitsLongerThanTheTurnBudgetAndThenTheApplicationShutdown() {
        var application = properties(new ClassPathResource("application.yml"));
        var compose = properties(new FileSystemResource("../deploy/compose.dev.yml"));
        var springGrace =
                DurationStyle.detectAndParse(
                        application.getProperty("spring.lifecycle.timeout-per-shutdown-phase"));
        var containerGrace =
                DurationStyle.detectAndParse(
                        compose.getProperty("services.backend.stop_grace_period"));

        assertThat(application.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(springGrace).isGreaterThan(RoleplayTurnDeadline.MAX_DURATION);
        assertThat(containerGrace).isGreaterThan(springGrace);
    }

    @Test
    void contextShutdownWaitsForAnActiveHttpTurnBeforeDestroyingItsWorkers() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var workers =
                new RoleplayHttpConfiguration()
                        .roleplayTurnWorkers(
                                new RoleplayHttpProperties(
                                        directory,
                                        16,
                                        65536L,
                                        Set.of(AudioFormat.WEBM),
                                        1,
                                        0,
                                        List.of(),
                                        null));
        var turns = new RoleplayTurnRunner(workers);
        var context = new AnnotationConfigServletWebServerApplicationContext();
        context.registerBean(
                "roleplayTurnWorkers",
                ThreadPoolExecutor.class,
                () -> workers,
                definition -> definition.setDestroyMethodName("shutdownNow"));
        context.registerBean(
                TomcatServletWebServerFactory.class,
                () -> {
                    var factory = new TomcatServletWebServerFactory(0);
                    factory.setShutdown(Shutdown.GRACEFUL);
                    return factory;
                });
        context.registerBean(
                "lifecycleProcessor",
                DefaultLifecycleProcessor.class,
                () -> {
                    var lifecycle = new DefaultLifecycleProcessor();
                    var application = properties(new ClassPathResource("application.yml"));
                    lifecycle.setTimeoutPerShutdownPhase(
                            DurationStyle.detectAndParse(
                                            application.getProperty(
                                                    "spring.lifecycle.timeout-per-shutdown-phase"))
                                    .toMillis());
                    return lifecycle;
                });
        context.registerBean(
                ServletContextInitializer.class,
                () ->
                        servletContext ->
                                servletContext
                                        .addServlet(
                                                "test-turn",
                                                blockingTurn(turns, entered, release, interrupted))
                                        .addMapping("/test-turn"));
        try (var clients = Executors.newFixedThreadPool(2);
                var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            context.refresh();
            var shutdown = context.getBean("webServerGracefulShutdown", SmartLifecycle.class);
            var request =
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://127.0.0.1:"
                                                    + context.getWebServer().getPort()
                                                    + "/test-turn"))
                            .timeout(Duration.ofSeconds(15))
                            .build();
            var response =
                    clients.submit(() -> http.send(request, HttpResponse.BodyHandlers.ofString()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var closing = clients.submit(context::close);
                long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (shutdown.isRunning() && System.nanoTime() < until) Thread.sleep(5);
                assertThat(shutdown.isRunning()).isFalse();
                assertThat(closing.isDone()).isFalse();
                assertThat(workers.isShutdown()).isFalse();

                release.countDown();
                var delivered = response.get(5, TimeUnit.SECONDS);
                assertThat(delivered.statusCode()).isEqualTo(200);
                assertThat(delivered.body()).isEqualTo("delivered");
                closing.get(5, TimeUnit.SECONDS);
                assertThat(interrupted).isFalse();
                assertThat(workers.isShutdown()).isTrue();
            } finally {
                release.countDown();
            }
        } finally {
            release.countDown();
            context.close();
            workers.shutdownNow();
        }
    }

    private HttpServlet blockingTurn(
            RoleplayTurnRunner turns,
            CountDownLatch entered,
            CountDownLatch release,
            AtomicBoolean interrupted) {
        return new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
                    throws IOException {
                String result =
                        turns.executeWithTimeout(
                                RoleplayTurnDeadline.start(Duration.ofSeconds(10)),
                                deadline -> {
                                    entered.countDown();
                                    try {
                                        if (!release.await(10, TimeUnit.SECONDS))
                                            throw new AssertionError("Test turn was not released");
                                    } catch (InterruptedException failure) {
                                        interrupted.set(true);
                                        Thread.currentThread().interrupt();
                                        throw new IllegalStateException(failure);
                                    }
                                    return "delivered";
                                },
                                () -> "timed-out");
                response.getWriter().write(result);
                response.flushBuffer();
            }
        };
    }

    private static Properties properties(Resource resource) {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(resource);
        return yaml.getObject();
    }
}
