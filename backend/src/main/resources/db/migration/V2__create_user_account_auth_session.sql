-- 강사 인증(S1-BAE-01, DEC-001 7절).
-- V1 의 classroom.instructor_id 에는 개발용 문자열("dev-instructor")이 들어 있어 UUID FK 로 바꿀 수 없다.
-- 운영 데이터가 아직 없으므로 기존 학급·아동 행을 먼저 지운다.
DELETE FROM child;
DELETE FROM classroom;

CREATE TABLE user_account (
    user_id       UUID PRIMARY KEY,
    -- 앱에서 trim·소문자로 정규화한 값만 저장한다.
    email         VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    org_name      VARCHAR(100),
    role          VARCHAR(20)  NOT NULL DEFAULT 'INSTRUCTOR'
                  CHECK (role IN ('INSTRUCTOR', 'OPERATOR')),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'WITHDRAWN')),
    created_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE auth_session (
    session_id UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES user_account (user_id),
    -- 토큰 원문은 저장하지 않는다. SHA-256 16진수 문자열(64자)만 저장한다.
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_auth_session_user_id ON auth_session (user_id);

ALTER TABLE classroom
    ALTER COLUMN instructor_id TYPE UUID USING instructor_id::uuid;

ALTER TABLE classroom
    ADD CONSTRAINT fk_classroom_instructor
        FOREIGN KEY (instructor_id) REFERENCES user_account (user_id);

CREATE INDEX idx_classroom_instructor_id ON classroom (instructor_id);
