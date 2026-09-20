CREATE TABLE flyway_smoke_test
(
    id          BIGINT PRIMARY KEY,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO flyway_smoke_test (id)
VALUES (1);
