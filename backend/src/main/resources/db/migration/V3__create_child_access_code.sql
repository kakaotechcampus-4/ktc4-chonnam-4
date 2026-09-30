CREATE TABLE child_access_code (
    code_id UUID PRIMARY KEY,
    child_id UUID NOT NULL REFERENCES child (child_id),
    instructor_id UUID NOT NULL REFERENCES user_account (user_id),
    idempotency_key UUID NOT NULL UNIQUE,
    code_digest VARCHAR(64) NOT NULL,
    derivation_counter INTEGER NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE UNIQUE INDEX uq_child_access_code_active_digest
    ON child_access_code (code_digest) WHERE active;

CREATE UNIQUE INDEX uq_child_access_code_active_child
    ON child_access_code (child_id) WHERE active;

CREATE INDEX idx_child_access_code_expiry ON child_access_code (expires_at);
