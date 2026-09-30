package com.neuringo.neuringobe;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        // 마이너 버전까지 고정한다. compose.yml 의 postgres 와 같은 버전으로 맞춘다.
        return new PostgreSQLContainer("postgres:18.6-alpine")
                .withDatabaseName("neuringo_test")
                .withUsername("test")
                .withPassword("test");
    }
}
